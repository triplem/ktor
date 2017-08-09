package org.jetbrains.ktor.tests

import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.util.*
import org.openjdk.jmh.annotations.*

@State(Scope.Benchmark)
open class ParametersBenchmark {
    val headers = parametersOf("A" to listOf("B"), "C" to listOf("D"))

    @Benchmark
    fun parametersOfSingle(): Parameters {
        return parametersOf("A" to listOf("B"))
    }

    @Benchmark
    fun parametersOfMany(): Parameters {
        return parametersOf("A" to listOf("B"), "C" to listOf("D"))
    }

    @Benchmark
    fun build(): Parameters {
        return Parameters.build {
            append("A", "B")
            append("C", "D")
        }
    }

    @Benchmark
    fun filter(): Parameters {
        return headers.filter { name, value -> true }
    }

    @Benchmark
    fun compression(): Parameters {
        return Parameters.build(true) {
            appendFiltered(headers) { name, value -> !name.equals(HttpHeaders.ContentLength, true) }
            append(HttpHeaders.ContentEncoding, "deflate")
        }
    }
}

/*
Benchmark                               Mode  Cnt       Score      Error   Units
ParametersBenchmark.build               thrpt   10   10062.523 ±  636.484  ops/ms
ParametersBenchmark.compression         thrpt   10    4501.705 ±   73.952  ops/ms
ParametersBenchmark.filter              thrpt   10    9073.771 ±  662.824  ops/ms
ParametersBenchmark.parametersOfMany    thrpt   10   13795.421 ± 1234.157  ops/ms
ParametersBenchmark.parametersOfSingle  thrpt   10  123127.741 ± 2750.144  ops/ms
 */

fun main(args: Array<String>) {
    benchmark(args) {
        run<ParametersBenchmark>()
    }
}
