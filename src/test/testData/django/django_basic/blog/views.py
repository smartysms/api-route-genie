from django.http import HttpResponse
from django.views.generic import ListView


class PostList(ListView):
    pass


def post_detail(request, slug):
    return HttpResponse(slug)
