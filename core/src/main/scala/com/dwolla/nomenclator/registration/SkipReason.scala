package com.dwolla.nomenclator.registration

import cats.data.NonEmptyChain
import com.dwolla.nomenclator.settings.SettingsError

/** Why a container port was not turned into a registration.
  */
enum SkipReason:
  case Ignored
  case ScriptCheck
  case TtlCheck

  /** Unpublished ports never reach `core` (every `PortBinding` has a host
    * port), so nothing here produces this case. It exists so the skipped-services
    * metric can report it, as zero.
    */
  case NoHostPort
  case InvalidSettings(errors: NonEmptyChain[SettingsError])
end SkipReason
