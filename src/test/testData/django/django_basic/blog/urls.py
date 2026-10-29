from django.urls import include, path

from . import views

app_name = "blog"

urlpatterns = [
    path("", views.PostList.as_view(), name="list"),
    path("<slug:slug>/", views.post_detail, name="post"),
    path("<slug:slug>/comments/", include("blog.comments.urls", namespace="c")),
]
