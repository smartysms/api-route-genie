from django.urls import path

from . import views

app_name = "shop"

urlpatterns = [
    path("", views.index, name="index"),
    path("item/<int:pk>/", views.Detail.as_view(), name="detail"),
    path("form/", views.ItemForm.as_view()),
    path("remove/<int:pk>/", views.Remove.as_view(), name="remove"),
    path("read/", views.only_get),
    path("write/", views.only_post),
    path("safe/", views.safe_view),
    path("both/", views.both),
    path("page/", views.Page.as_view()),
    path("limited/", views.Limited.as_view()),
]
urlpatterns += [path("x/", views.x)]
urlpatterns.append(path("appended/", views.x, name="appended"))
