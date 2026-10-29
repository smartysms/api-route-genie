from django.urls import include, path
from rest_framework.routers import DefaultRouter

from . import views

router = DefaultRouter()
router.register("books", views.BookViewSet, basename="book")

urlpatterns = [
    path("authors/", views.AuthorCreate.as_view()),
    path("login/", views.LoginView.as_view()),
    path("raw/", views.RawView.as_view()),
    path("", include(router.urls)),
]
