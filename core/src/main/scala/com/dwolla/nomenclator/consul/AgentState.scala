package com.dwolla.nomenclator.consul

final case class AgentState(services: List[AgentService], checks: List[AgentCheck])
