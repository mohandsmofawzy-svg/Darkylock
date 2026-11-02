package dev.mo.darkylock.core

import org.json.JSONArray

fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }
