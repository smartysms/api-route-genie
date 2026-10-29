from django.http import HttpResponse
from django.views.decorators.http import require_POST
from django.views.generic import CreateView, FormView, View

from .forms import ContactForm, ProductForm, UploadForm
from .models import Category


class ContactView(FormView):
    form_class = ContactForm


class UploadView(FormView):
    form_class = UploadForm


class ProductCreate(CreateView):
    form_class = ProductForm


class CategoryCreate(CreateView):
    model = Category
    fields = "__all__"


class PlainView(View):
    def post(self, request):
        return HttpResponse("ok")


@require_POST
def subscribe(request):
    form = ContactForm(request.POST)
    return HttpResponse("ok")


@require_POST
def raw(request):
    return HttpResponse(request.POST["x"])
