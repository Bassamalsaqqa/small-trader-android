package com.example

import java.math.BigDecimal

operator fun BigDecimal.compareTo(other: Int): Int = this.compareTo(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.compareTo(other: Double): Int = this.compareTo(BigDecimal(other.toString()))
operator fun BigDecimal.plus(other: Int): BigDecimal = this.add(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.minus(other: Int): BigDecimal = this.subtract(BigDecimal.valueOf(other.toLong()))
operator fun BigDecimal.times(other: Int): BigDecimal = this.multiply(BigDecimal.valueOf(other.toLong()))

operator fun Int.compareTo(other: BigDecimal): Int = BigDecimal.valueOf(this.toLong()).compareTo(other)
operator fun Int.plus(other: BigDecimal): BigDecimal = BigDecimal.valueOf(this.toLong()).add(other)
operator fun Int.minus(other: BigDecimal): BigDecimal = BigDecimal.valueOf(this.toLong()).subtract(other)
operator fun Int.times(other: BigDecimal): BigDecimal = BigDecimal.valueOf(this.toLong()).multiply(other)
