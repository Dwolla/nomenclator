package com.dwolla.nomenclator.ecs.smithy4s

/** How a task definition lookup was answered.
  */
enum LookupResult:

  /** From the cache, without calling ECS.
    */
  case Cached

  /** By ECS.
    */
  case Fetched

  /** Not at all: the ECS call failed or was cancelled.
    */
  case Failed
end LookupResult
