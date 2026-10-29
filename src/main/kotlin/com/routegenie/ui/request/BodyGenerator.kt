package com.routegenie.ui.request

import com.routegenie.client.BodyTemplate
import com.routegenie.client.BodyTemplateSource
import com.routegenie.model.Endpoint

sealed interface BodyGeneration {
    /** Safe to put into the editor. */
    data class Ready(val template: BodyTemplate) : BodyGeneration

    /** The editor holds text the user wrote; the UI must ask replace or cancel before using [template]. */
    data class NeedsConfirmation(val template: BodyTemplate) : BodyGeneration

    /** The code does not declare a request body for this endpoint; nothing is generated. */
    data object Unavailable : BodyGeneration
}

/** The "Generate body" decision, free of Swing: asks the source (off the UI thread, it is `suspend`) and never invents a body. */
object BodyGenerator {
    suspend fun generate(source: BodyTemplateSource, endpoint: Endpoint, currentText: String): BodyGeneration {
        val template = source.templateFor(endpoint) ?: return BodyGeneration.Unavailable
        return if (currentText.isBlank()) BodyGeneration.Ready(template) else BodyGeneration.NeedsConfirmation(template)
    }

    fun apply(form: RequestForm, template: BodyTemplate) {
        form.bodyKind = BodyType.of(template.contentType)
        form.bodyText = template.text
    }
}
