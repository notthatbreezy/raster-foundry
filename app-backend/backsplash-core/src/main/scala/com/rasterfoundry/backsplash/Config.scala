package com.rasterfoundry.backsplash

import com.typesafe.config.ConfigFactory

object Config {
  private val config = ConfigFactory.load()

  object cache {
    private val cacheConfig = config.getConfig("cache")

    val maxNumberItems = cacheConfig.getInt("maxNumberItems")
    val enable = cacheConfig.getBoolean("enable")

  }
}
