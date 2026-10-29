from flask import Blueprint

bp = Blueprint("auth", __name__, url_prefix="/auth")


@bp.route("/login", methods=["GET", "POST"])
def login():
    ...


@bp.get("/me")
def me():
    ...
