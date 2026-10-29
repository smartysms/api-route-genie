package com.routegenie.model

/** The notation of the path templates shown in the endpoint list: `/users/{user_id}`, unknown parts as `{?}`. */
object PathSyntax {
    /** Stands in for a part of a path that is computed at runtime and cannot be determined statically. */
    const val UNKNOWN_SEGMENT = "{?}"
}
