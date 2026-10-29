from flask import request
from flask.views import MethodView


class UserAPI(MethodView):
    def get(self):
        return {}

    def post(self):
        payload = request.json
        return payload
