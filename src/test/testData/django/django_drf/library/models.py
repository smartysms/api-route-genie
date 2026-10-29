from django.db import models


class Book(models.Model):
    title = models.CharField(max_length=100)


class Author(models.Model):
    name = models.CharField(max_length=100)
