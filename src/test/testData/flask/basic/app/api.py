from flask import Blueprint

# The own prefix /v1 is replaced by the /v2 given at registration.
api_bp = Blueprint("api", __name__, url_prefix="/v1")
child = Blueprint("items", __name__, url_prefix="/items")


@child.route("/<int:item_id>")
def get_item(item_id):
    ...


@api_bp.route("/status")
def status():
    ...


api_bp.register_blueprint(child)
