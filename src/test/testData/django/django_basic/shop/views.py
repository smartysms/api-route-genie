from django.http import HttpResponse
from django.views import View
from django.views.decorators.http import require_GET, require_POST, require_http_methods, require_safe
from django.views.generic import CreateView, DeleteView, TemplateView


def index(request):
    return HttpResponse("ok")


def x(request):
    return HttpResponse("x")


def ping(request):
    return HttpResponse("pong")


def archive(request, year):
    return HttpResponse(year)


def item_api(request, item_id):
    return HttpResponse(item_id)


@require_GET
def only_get(request):
    return HttpResponse("g")


@require_POST
def only_post(request):
    return HttpResponse("p")


@require_safe
def safe_view(request):
    return HttpResponse("s")


@require_http_methods(["GET", "POST"])
def both(request):
    return HttpResponse("b")


class Detail(View):
    def get(self, request, pk):
        return HttpResponse(pk)

    def post(self, request, pk):
        return HttpResponse(pk)


class ItemForm(CreateView):
    pass


class Remove(DeleteView):
    pass


class Page(TemplateView):
    template_name = "page.html"


class Limited(View):
    http_method_names = ["get"]

    def get(self, request):
        return HttpResponse("l")

    def post(self, request):
        return HttpResponse("never")
