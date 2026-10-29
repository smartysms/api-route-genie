package com.routegenie.model

/** The web frameworks whose routes can be listed. The one place where a framework's display name is defined. */
enum class Framework(val displayName: String) {
    FASTAPI("FastAPI"),
    FLASK("Flask"),
    DJANGO("Django"),
    DRF("Django REST Framework"),
}
