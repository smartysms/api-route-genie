from flask import Flask

from . import views
from .api import bp as api_bp


def create_app():
    app = Flask(__name__)
    app.register_blueprint(api_bp, url_prefix="/api")
    app.add_url_rule("/users/", view_func=views.UserAPI.as_view("users"))
    return app
