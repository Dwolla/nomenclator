package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.model.*

final case class AgentCheck(checkId: String, serviceId: Option[ServiceId], definition: Option[CheckDefinition])
