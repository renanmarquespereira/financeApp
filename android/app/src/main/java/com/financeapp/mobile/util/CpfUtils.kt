package com.financeapp.mobile.util

fun normalizeCpf(
    value: String
): String =
    value.filter(Char::isDigit)
        .take(11)

fun formatCpfInput(
    value: String
): String {
    val digits =
        normalizeCpf(value)

    return buildString {
        digits.forEachIndexed {
                index,
                char ->
            append(char)

            when (index) {
                2,
                5 ->
                    if (
                        index <
                        digits.lastIndex
                    ) {
                        append('.')
                    }

                8 ->
                    if (
                        index <
                        digits.lastIndex
                    ) {
                        append('-')
                    }
            }
        }
    }
}

fun isValidCpf(
    value: String
): Boolean {
    val cpf =
        normalizeCpf(value)

    if (cpf.length != 11) {
        return false
    }

    if (cpf.all { it == cpf[0] }) {
        return false
    }

    fun digit(
        length: Int
    ): Int {
        var sum = 0
        var weight =
            length + 1

        for (
            index in 0
            until length
        ) {
            sum +=
                cpf[index]
                    .digitToInt() *
                    weight
            weight -= 1
        }

        val result =
            (sum * 10) % 11

        return if (
            result == 10
        ) {
            0
        } else {
            result
        }
    }

    return cpf[9].digitToInt() ==
        digit(9) &&
        cpf[10].digitToInt() ==
        digit(10)
}
