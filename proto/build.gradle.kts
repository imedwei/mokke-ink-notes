plugins {
    kotlin("jvm")
    id("com.squareup.wire")
}

wire {
    kotlin {}
}

dependencies {
    api("com.squareup.wire:wire-runtime:5.1.0")
}
