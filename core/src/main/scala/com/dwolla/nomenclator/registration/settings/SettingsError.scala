package com.dwolla.nomenclator.registration.settings

enum SettingsError:

  /** A `check_*` setting that is not a Go duration.
    */
  case InvalidDuration(key: String, value: String)
end SettingsError
