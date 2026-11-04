package com.dwolla.nomenclator.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.reconcile.{DefinitionLookups, Mode}
import com.dwolla.nomenclator.settings.GoDuration
import com.comcast.ip4s.*
import com.dwolla.nomenclator.ecs.agent.Http4sEcsAgentIntrospection
import org.http4s.Uri
import org.typelevel.ci.*
import smithy4s.aws.AwsRegion

import scala.concurrent.duration.*

/** Nomenclator's settings, from environment variables. Settings left unset here
  * are discovered at startup: see
  * [[com.dwolla.nomenclator.startup.StartupDiscovery]].
  *
  * @param hostName
  *   `NOMENCLATOR_HOSTNAME`; otherwise the local host's name
  * @param address
  *   `NOMENCLATOR_ADDRESS`; otherwise the Consul agent's advertised address
  * @param credentialsRelativeUri
  *   `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`, which ECS sets for a task with a
  *   task role, as a path on ECS's credentials host; preferred over
  *   `credentialsFullUri`
  * @param credentialsFullUri
  *   `AWS_CONTAINER_CREDENTIALS_FULL_URI`, the standard AWS variable for a
  *   credentials endpoint elsewhere, such as a local stand-in
  * @param awsRegion
  *   `AWS_REGION`; otherwise the region the container instance's ARN names
  * @param ecsEndpoint
  *   `AWS_ENDPOINT_URL_ECS`, the standard AWS variable that replaces the
  *   regional ECS endpoint, such as with a local stand-in
  */
final case class NomenclatorConfig(
                                    mode: Mode,
                                    consulAddress: Uri,
                                    consulToken: Option[Secret[String]],
                                    interval: FiniteDuration,
                                    passTimeout: FiniteDuration,
                                    lookupTimeout: FiniteDuration,
                                    failureLimit: FiniteDuration,
                                    globalTags: List[Tag],
                                    hostName: Option[HostName],
                                    address: Option[ServiceAddress],
                                    ecsAgent: Uri,
                                    credentialsRelativeUri: Option[Uri.Path],
                                    credentialsFullUri: Option[Uri],
                                    awsRegion: Option[AwsRegion],
                                    ecsEndpoint: Option[Uri]
):

  /** The settings as structured log context. The Consul token is only ever
    * shown as redacted.
    */
  def logContext: Map[String, String] =
    Map(
      "mode" -> mode.value,
      "consul_address" -> consulAddress.renderString,
      "consul_token" -> consulToken.fold("<unset>")(_ => "<redacted>"),
      "interval" -> GoDuration.render(interval),
      "pass_timeout" -> GoDuration.render(passTimeout),
      "lookup_timeout" -> GoDuration.render(lookupTimeout),
      "failure_limit" -> GoDuration.render(failureLimit),
      "global_tags" -> globalTags.map(_.value).mkString(","),
      "ecs_agent" -> ecsAgent.renderString
    ) ++
      ecsEndpoint.map("ecs_endpoint" -> _.renderString) ++
      credentialsFullUri.map("credentials_full_uri" -> _.renderString)
end NomenclatorConfig

object NomenclatorConfig:
  private val ModeVariable = "NOMENCLATOR_MODE"
  private val IntervalVariable = "NOMENCLATOR_INTERVAL"
  private val PassTimeoutVariable = "NOMENCLATOR_PASS_TIMEOUT"
  private val LookupTimeoutVariable = "NOMENCLATOR_LOOKUP_TIMEOUT"
  private val FailureLimitVariable = "NOMENCLATOR_FAILURE_LIMIT"

  /** Reads the process's environment variables.
    */
  def load[F[_]: Async]: F[NomenclatorConfig] =
    fromEnvironment(ciris.env).load[F]

  /** Reads the variables through `variable`, reporting every invalid one at
    * once, each by name.
    */
  def fromEnvironment(variable: String => ConfigValue[Effect, String]): ConfigValue[Effect, NomenclatorConfig] =
    def duration(name: String, default: FiniteDuration) =
      variable(name).as(using positiveDuration).default(default)

    (
      variable(ModeVariable).as(using mode).default(Mode.Shadow),
      variable("CONSUL_HTTP_ADDR")
        .default("127.0.0.1:8500")
        .as(using consulAddress),
      variable("CONSUL_HTTP_TOKEN").secret.option
        .map(_.filter(_.value.nonEmpty)),
      duration(IntervalVariable, 5.seconds),
      duration(PassTimeoutVariable, 30.seconds),
      duration(LookupTimeoutVariable, 5.seconds),
      duration(FailureLimitVariable, 2.minutes),
      variable("NOMENCLATOR_TAGS").option.map(_.fold(Nil)(tags)),
      variable("NOMENCLATOR_HOSTNAME").as(using hostName).option,
      variable("NOMENCLATOR_ADDRESS")
        .as(using nonEmpty)
        .map(ServiceAddress(_))
        .option,
      variable("NOMENCLATOR_ECS_AGENT_URL")
        .as(using url)
        .default(Http4sEcsAgentIntrospection.defaultBase),
      variable("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
        .as(using absolutePath)
        .option,
      variable("AWS_CONTAINER_CREDENTIALS_FULL_URI")
        .as(using credentialsUrl)
        .option,
      variable("AWS_REGION").as(using nonEmpty).map(AwsRegion(_)).option,
      variable("AWS_ENDPOINT_URL_ECS").as(using endpointUrl).option
    ).parMapN(NomenclatorConfig.apply)
      .flatMap(config =>
        timingProblems(config).toNel.fold(
          ConfigValue.default(config)
        )(problems =>
          ConfigValue.failed(
            problems.map(ConfigError(_)).reduceLeft(_.and(_))
          )
        )
      )
  end fromEnvironment

  /** How the timing settings contradict one another. A pass must be able to
    * wait out its definition lookups with one lookup timeout to spare for the
    * rest of the pass, finish before the failure limit, and finish before the
    * next one starts.
    */
  private def timingProblems(config: NomenclatorConfig): List[String] =
    import config.*
    def shown(name: String, value: FiniteDuration) =
      s"$name (${GoDuration.render(value)})"
    val shortestPass = DefinitionLookups.deadline(lookupTimeout) + lookupTimeout
    List(
      Option.when(passTimeout < shortestPass)(
        s"${shown(PassTimeoutVariable, passTimeout)} must be at least ${GoDuration.render(shortestPass)}: the lookup phase's deadline, two and a half times ${shown(LookupTimeoutVariable, lookupTimeout)}, plus one more $LookupTimeoutVariable for the rest of the pass"
      ),
      Option.when(passTimeout >= failureLimit)(
        s"${shown(PassTimeoutVariable, passTimeout)} must be less than ${shown(FailureLimitVariable, failureLimit)}"
      ),
      Option.when(interval > passTimeout)(
        s"${shown(IntervalVariable, interval)} must not be more than ${shown(PassTimeoutVariable, passTimeout)}"
      )
    ).flatten
  end timingProblems

  private val mode: ConfigDecoder[String, Mode] =
    ConfigDecoder[String].mapOption(
      s"a mode (${Mode.values.map(_.value).mkString(" or ")})"
    )(Mode.fromString)

  private val positiveDuration: ConfigDecoder[String, FiniteDuration] =
    ConfigDecoder[String].mapEither: (key, value) =>
      GoDuration
        .parse(value)
        .toOption
        .filter(_ > Duration.Zero)
        .toRight(
          ConfigError.decode("a positive duration, such as 5s", key, value)
        )

  private val nonEmpty: ConfigDecoder[String, String] =
    ConfigDecoder[String].mapEither: (key, value) =>
      Either.cond(
        value.nonEmpty,
        value,
        ConfigError.decode("a non-empty value", key, value)
      )

  private val hostName: ConfigDecoder[String, HostName] =
    ConfigDecoder[String].mapEither: (key, value) =>
      HostName
        .from(value)
        .leftMap(_ => ConfigError.decode("a host name without ':'", key, value))

  /** An absolute URL, with a scheme and a host.
    */
  private val url: ConfigDecoder[String, Uri] =
    ConfigDecoder[String].mapEither: (key, value) =>
      Uri
        .fromString(value)
        .toOption
        .filter(u => u.scheme.isDefined && u.host.exists(_.value.nonEmpty))
        .toRight(ConfigError.decode("a URL", key, value))

  /** A path starting with `/`, and nothing else: no authority, query, or
    * fragment. Anything more could move the request off the host it is meant
    * for.
    */
  private val absolutePath: ConfigDecoder[String, Uri.Path] =
    ConfigDecoder[String].mapEither: (key, value) =>
      Uri
        .fromString(value)
        .toOption
        .filter(u =>
          value.startsWith("/") && u.scheme.isEmpty && u.authority.isEmpty &&
            u.query.isEmpty && u.fragment.isEmpty
        )
        .map(_.path)
        .toRight(ConfigError.decode("a URI path starting with /", key, value))

  /** As AWS SDKs allow it: HTTPS to any host, but plain HTTP only to this host
    * or to the addresses ECS and EKS serve credentials on, so that credentials
    * never cross the network unencrypted.
    */
  private val credentialsUrl: ConfigDecoder[String, Uri] =
    url.mapEither: (key, uri) =>
      Either.cond(
        uri.scheme.contains(Uri.Scheme.https) || plainHttpAllowed(uri),
        uri,
        ConfigError.decode(
          "an https URL, or an http URL to a loopback address, 169.254.170.2, 169.254.170.23, or fd00:ec2::23",
          key,
          uri.renderString
        )
      )

  private val containerCredentialsHosts: Set[IpAddress] = Set(
    ipv4"169.254.170.2",
    ipv4"169.254.170.23",
    ipv6"fd00:ec2::23"
  )

  private def plainHttpAllowed(uri: Uri): Boolean =
    uri.scheme.contains(Uri.Scheme.http) && uri.host.exists:
      case Uri.Ipv4Address(address) =>
        address.isLoopback || containerCredentialsHosts(address)
      case Uri.Ipv6Address(address) =>
        address.isLoopback || containerCredentialsHosts(address)
      case Uri.RegName(name) => name == ci"localhost"

  /** Only the scheme and authority replace the regional endpoint's, so a path
    * would be ignored; it is refused instead.
    */
  private val endpointUrl: ConfigDecoder[String, Uri] =
    url.mapEither: (key, uri) =>
      Either.cond(
        uri.path.isEmpty || uri.path == Uri.Path.Root,
        uri,
        ConfigError.decode("a URL without a path", key, uri.renderString)
      )

  /** As the Consul CLI reads it: plain HTTP unless a scheme is given.
    */
  private val consulAddress: ConfigDecoder[String, Uri] =
    ConfigDecoder[String]
      .map(value => if (value.contains("://")) value else s"http://$value")
      .as(using url)

  /** Split on commas with nothing trimmed or dropped, as registrator splits its
    * `-tags`.
    */
  private def tags(value: String): List[Tag] =
    if (value.isEmpty) Nil else value.split(",", -1).toList.map(Tag(_))
end NomenclatorConfig
