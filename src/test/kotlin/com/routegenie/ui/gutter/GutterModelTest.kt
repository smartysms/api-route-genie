package com.routegenie.ui.gutter

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.settings.SettingsPolicy
import com.routegenie.testing.ObjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts: the line index, the labels, the activation text gate and the settings default. */
class GutterModelTest {
    private fun endpoint(method: HttpMethod, path: String, file: String, line: Int, handler: String = "h") =
        Endpoint(method, path, Framework.FASTAPI, Handler(handler, "m.$handler"), SourceRef(file, line * 10, line))

    @Test
    fun `methods of one route share one label and one icon`() {
        val model = GutterModel.build(
            RouteSnapshot(1, listOf(endpoint(HttpMethod.POST, "/multi", "a.py", 5), endpoint(HttpMethod.GET, "/multi", "a.py", 5))),
        )
        val entry = model.entryAt("a.py", 5)!!
        assertEquals("GET, POST /multi", entry.label)
        assertEquals(1, entry.choices.size)
        assertEquals(setOf(5), model.linesOf("a.py")!!.keys)
        assertNull(model.entryAt("a.py", 6))
        assertNull(model.linesOf("b.py"))
    }

    @Test
    fun `one declaration mounted under two prefixes offers both paths`() {
        val model = GutterModel.build(
            RouteSnapshot(1, listOf(endpoint(HttpMethod.GET, "/v1/items", "a.py", 3), endpoint(HttpMethod.GET, "/latest/items", "a.py", 3))),
        )
        val entry = model.entryAt("a.py", 3)!!
        assertEquals(listOf("GET /latest/items", "GET /v1/items"), entry.choices.map { it.text })
        assertEquals("GET /latest/items; GET /v1/items", entry.label)
    }

    @Test
    fun `choices are capped`() {
        val endpoints = (1..Limits.GUTTER_MAX_CHOICES + 5).map { endpoint(HttpMethod.GET, "/p$it", "a.py", 1) }
        assertEquals(Limits.GUTTER_MAX_CHOICES, GutterModel.build(RouteSnapshot(1, endpoints)).entryAt("a.py", 1)!!.choices.size)
    }

    @Test
    fun `models are equal when markers are equal whatever the generation`() {
        val list = listOf(endpoint(HttpMethod.GET, "/x", "a.py", 2))
        assertEquals(GutterModel.build(RouteSnapshot(1, list)), GutterModel.build(RouteSnapshot(2, list)))
        assertNotEquals(GutterModel.build(RouteSnapshot(1, list)), GutterModel.build(RouteSnapshot(1, listOf(endpoint(HttpMethod.GET, "/x", "a.py", 3)))))
        assertTrue(GutterModel.build(RouteSnapshot(1, emptyList())).isEmpty)
    }

    @Test
    fun `the index holds no psi file or project`() {
        val model = GutterModel.build(RouteSnapshot(1, (1..50).map { endpoint(HttpMethod.GET, "/p$it", "a$it.py", it) }))
        ObjectGraph.assertNoInstanceOf("gutter model", model, PsiElement::class.java, VirtualFile::class.java, Project::class.java)
    }

    @Test
    fun `route files are recognized by import root and routing construct`() {
        assertTrue(RouteFileText.looksLikeRouteFile("from fastapi import APIRouter\nr = APIRouter()\n@r.get('/x')\ndef f(): ...\n"))
        assertTrue(RouteFileText.looksLikeRouteFile("from flask import Flask\napp = Flask(__name__)\n@app.route('/')\ndef f(): ...\n"))
        assertTrue(RouteFileText.looksLikeRouteFile("from django.urls import path\nurlpatterns = [path('a/', v)]\n"))
    }

    @Test
    fun `ordinary python files do not start discovery`() {
        assertFalse(RouteFileText.looksLikeRouteFile("import os\nd = {}\nx = d.get('a')\nprint(os.getcwd())\n"))
        assertFalse(RouteFileText.looksLikeRouteFile("from fastapi import status\nOK = status.HTTP_200_OK\n"))
        assertFalse(RouteFileText.looksLikeRouteFile(""))
    }

    @Test
    fun `gutter default follows the paid plugin unless the user chose`() {
        assertTrue(SettingsPolicy.showGutterIcons(null, paidPythonActive = false))
        assertFalse(SettingsPolicy.showGutterIcons(null, paidPythonActive = true))
        assertTrue(SettingsPolicy.showGutterIcons(true, paidPythonActive = true))
        assertFalse(SettingsPolicy.showGutterIcons(false, paidPythonActive = false))
    }
}
