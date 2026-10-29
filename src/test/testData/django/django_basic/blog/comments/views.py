from django.http import HttpResponse
from django.views import View
from django.views.decorators.http import require_http_methods


class CommentView(View):
    def get(self, request):
        return HttpResponse("c")

    def put(self, request):
        return HttpResponse("c")


@require_http_methods(["DELETE"])
def remove(request, comment_id):
    return HttpResponse("d")
