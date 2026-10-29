from django.urls import include, path
from rest_framework.routers import DefaultRouter, SimpleRouter

from . import views
from .viewsets import AuthorViewSet, BookViewSet, ReaderViewSet, TagViewSet

router = DefaultRouter()
router.register("books", BookViewSet, basename="book")
router.register(r"authors", AuthorViewSet)
router.register("readers", ReaderViewSet, basename="reader")

simple = SimpleRouter(trailing_slash=False)
simple.register("tags", TagViewSet, basename="tag")

urlpatterns = [
    path("status/", views.StatusView.as_view(), name="status"),
    path("ping/", views.ping, name="ping"),
    path("notes/", views.NoteList.as_view()),
    path("notes/<int:pk>/", views.NoteDetail.as_view()),
    path("both/", views.both),
    path("manual/", views.ManualViewSet.as_view({"get": "list", "post": "create"})),
    path("v2/", include((simple.urls, "v2"), namespace="v2")),
    path("", include(router.urls)),
]
