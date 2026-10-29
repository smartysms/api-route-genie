package com.routegenie.frameworks.common

import com.routegenie.Limits
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PythonPlatformTestCase

/**
 * Re-extracting one route file of a realistic size (about 700 lines, 60 routes with docstrings, type hints and helper
 * code) from its PSI. This is the whole per-edit cost of the first stage; linking is measured separately.
 */
class ExtractionPerfTest : PythonPlatformTestCase() {
    private fun fastApiFile() = buildString {
        append("from typing import List, Optional\nfrom fastapi import APIRouter, Depends, HTTPException\nfrom pydantic import BaseModel\n")
        append("from .config import PREFIX\n\nrouter = APIRouter(prefix=PREFIX + '/items', tags=['items'])\n\n")
        for (i in 0 until ROUTES) {
            append("class Item$i(BaseModel):\n    name: str\n    price: float = 0.0\n    tags: List[str] = []\n\n")
            append("@router.get('/item$i/{item_id}', response_model=Item$i, summary='Read item $i')\n")
            append("async def read_item_$i(item_id: int, q: Optional[str] = None, user=Depends(get_user)):\n")
            append("    \"\"\"Read item $i.\n\n    Longer text that real route files carry in their docstrings.\n    \"\"\"\n")
            append("    if item_id < 0:\n        raise HTTPException(status_code=404, detail='missing')\n    return {'id': item_id, 'q': q}\n\n")
        }
    }

    private fun flaskFile() = buildString {
        append("from flask import Blueprint, jsonify, request\n\nbp = Blueprint('items', __name__, url_prefix='/items')\n\n")
        for (i in 0 until ROUTES) {
            append("@bp.route('/item$i/<int:item_id>', methods=['GET', 'POST'])\n")
            append("def item_$i(item_id):\n    \"\"\"Item $i.\"\"\"\n    data = request.get_json(silent=True) or {}\n")
            append("    if not data:\n        return jsonify({'id': item_id})\n    return jsonify(data), 201\n\n")
        }
    }

    private fun djangoFile() = buildString {
        append("from django.urls import path, include\nfrom . import views\n\nurlpatterns = [\n")
        for (i in 0 until ROUTES * 2) append("    path('r$i/<int:pk>/', views.view_$i, name='view-$i'),\n")
        append("    path('more/', include('more.urls')),\n]\n")
    }

    private fun measure(label: String, provider: com.routegenie.discovery.RouteProvider, name: String, text: String) {
        val psi = pythonFile(name, text)
        assertNotNull(provider.extract(psi))
        val lines = text.lines().size
        val result = PerfBudget.assertWithin(label, Limits.BUDGET_REEXTRACT_ONE_FILE_MS) { provider.extract(psi) }
        println("re-extract $label ($lines lines): median ${result.medianMs} ms")
    }

    fun testReExtractingOneRealSizeFastApiFileIsWithinTheBudget() = measure("reextract-fastapi-real-size-file", FastApiRouteProvider(), "api/items.py", fastApiFile())

    fun testReExtractingOneRealSizeFlaskFileIsWithinTheBudget() = measure("reextract-flask-real-size-file", FlaskRouteProvider(), "api/items_bp.py", flaskFile())

    fun testReExtractingOneRealSizeDjangoUrlsFileIsWithinTheBudget() = measure("reextract-django-real-size-file", DjangoRouteProvider(), "shop/urls.py", djangoFile())

    private companion object {
        const val ROUTES = 60
    }
}
