from rest_framework import mixins, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response

from .models import Author, Book


class BookViewSet(viewsets.ModelViewSet):
    queryset = Book.objects.all()
    lookup_field = "isbn"

    @action(detail=True, methods=["post", "delete"], url_path="reset-copies")
    def reset(self, request, isbn=None):
        return Response("reset")

    @action(detail=False)
    def recent(self, request):
        return Response([])

    @action(detail=False, methods=["get"], url_name="by-author")
    def by_author(self, request):
        return Response([])


class AuthorViewSet(viewsets.ModelViewSet):
    queryset = Author.objects.all()
    lookup_url_kwarg = "author_id"


class ReaderViewSet(viewsets.ReadOnlyModelViewSet):
    pass


class TagViewSet(mixins.ListModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet):
    pass
