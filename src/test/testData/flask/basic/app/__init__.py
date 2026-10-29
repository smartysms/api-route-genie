from flask import Flask

from . import views
from .api import api_bp
from .auth import bp as auth_bp


def create_app():
    app = Flask(__name__)
    app.register_blueprint(auth_bp)
    app.register_blueprint(api_bp, url_prefix="/v2")
    app.add_url_rule("/users/", view_func=views.UserAPI.as_view("users"))

    @app.route("/health")
    def health():
        return "ok"

    return app
