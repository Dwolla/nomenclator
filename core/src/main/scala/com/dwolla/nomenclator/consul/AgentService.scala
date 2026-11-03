package com.dwolla.nomenclator.consul

import com.dwolla.nomenclator.model.*

final case class AgentService(
                               id: ServiceId,
                               name: ServiceName,
                               tags: List[Tag],
                               address: String,
                               port: Int
)
