import uuid

from django.db import models


class TimeStamped(models.Model):
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        abstract = True


class Author(TimeStamped):
    name = models.CharField(max_length=50)
    bio = models.TextField(blank=True)


class Publisher(models.Model):
    code = models.UUIDField(primary_key=True, default=uuid.uuid4)
    title = models.CharField(max_length=80)


class Book(TimeStamped):
    FORMATS = [("print", "Print"), ("ebook", "E-book")]

    title = models.CharField(max_length=100)
    isbn = models.SlugField(unique=True)
    pages = models.PositiveIntegerField(default=1)
    book_format = models.CharField(max_length=10, choices=FORMATS, default="print")
    author = models.ForeignKey(Author, on_delete=models.CASCADE)
    publisher = models.ForeignKey(Publisher, null=True, on_delete=models.SET_NULL)
    cover = models.CharField(max_length=200, blank=True)
