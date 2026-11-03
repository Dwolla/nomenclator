package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.model.*

final case class AgentCheck(checkId: CheckId, serviceId: Option[ServiceId], definition: Option[CheckDefinition])
