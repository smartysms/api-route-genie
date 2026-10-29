from flask import Blueprint, request
from flask_pydantic import validate
from pydantic import BaseModel

bp = Blueprint("api", __name__)


class Item(BaseModel):
    name: str
    qty: int = 1


@bp.post("/items")
@validate(body=Item)
def create_item():
    return {}


@bp.post("/plain")
def create_plain():
    data = request.get_json()
    name = data["name"]
    return {"name": name}


@bp.put("/form")
def update_form():
    values = request.form
    return dict(values)
