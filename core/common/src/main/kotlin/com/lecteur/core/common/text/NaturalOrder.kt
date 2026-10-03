package com.lecteur.core.common.text

/** "Episode 2" before "Episode 10", case and accents ignored: the order a person expects in a folder listing. */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val x = TextFold.fold(a)
        val y = TextFold.fold(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val endX = digitRunEnd(x, i)
                val endY = digitRunEnd(y, j)
                val numX = x.substring(i, endX).trimStart('0')
                val numY = y.substring(j, endY).trimStart('0')
                // Longer number is bigger once leading zeros are gone; same length compares digit by digit
                val byLength = numX.length.compareTo(numY.length)
                if (byLength != 0) return byLength
                val byDigits = numX.compareTo(numY)
                if (byDigits != 0) return byDigits
                i = endX
                j = endY
            } else {
                if (cx != cy) return cx.compareTo(cy)
                i++
                j++
            }
        }
        val byRemaining = (x.length - i).compareTo(y.length - j)
        // Same folded name: fall back to the raw text so the order is total and stable
        return if (byRemaining != 0) byRemaining else a.compareTo(b)
    }

    private fun digitRunEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }
}
