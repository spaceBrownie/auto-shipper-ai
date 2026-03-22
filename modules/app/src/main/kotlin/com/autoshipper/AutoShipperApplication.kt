package com.autoshipper

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.autoshipper"])
@EnableScheduling
@EnableAsync
class AutoShipperApplication

fun main(args: Array<String>) {
    runApplication<AutoShipperApplication>(*args)
}
