from django.urls import path

from . import views

urlpatterns = [
    path("contact/", views.ContactView.as_view()),
    path("upload/", views.UploadView.as_view()),
    path("products/new/", views.ProductCreate.as_view()),
    path("categories/new/", views.CategoryCreate.as_view()),
    path("plain/", views.PlainView.as_view()),
    path("subscribe/", views.subscribe),
    path("raw/", views.raw),
]
