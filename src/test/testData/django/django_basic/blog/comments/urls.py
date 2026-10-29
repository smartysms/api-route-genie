from django.urls import path

from .views import CommentView, remove

urlpatterns = [
    path("", CommentView.as_view(), name="all"),
    path("<int:comment_id>/delete/", remove, name="remove"),
]
