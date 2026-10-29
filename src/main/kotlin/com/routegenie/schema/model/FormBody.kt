package com.routegenie.schema.model

import com.routegenie.client.UrlSupport

/**
 * Renders a sample object as an `application/x-www-form-urlencoded` body: scalars as `name=value`, a list of scalars as
 * repeated names, null members left out (a form cannot say null). Nested objects have no form encoding, so the result is
 * null instead of a guess.
 */
object FormBody {
    fun encode(value: Json): String? {
        val fields = (value as? Json.Obj)?.fields ?: return null
        val pairs = ArrayList<String>()
        for ((name, member) in fields) {
            when (member) {
                Json.Null -> Unit
                is Json.Arr -> for (item in member.items) pairs += pair(name, item) ?: return null
                else -> pairs += pair(name, member) ?: return null
            }
        }
        return pairs.joinToString("&")
    }

    private fun pair(name: String, value: Json): String? {
        val text = when (value) {
            is Json.Str -> value.value
            is Json.Num -> JsonText.number(value.value)
            is Json.Bool -> if (value.value) "true" else "false"
            else -> return null
        }
        return UrlSupport.encodeForm(name) + "=" + UrlSupport.encodeForm(text)
    }
}
