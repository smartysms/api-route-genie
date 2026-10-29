from rest_framework import serializers

from .models import Author, Book


class AuthorSerializer(serializers.ModelSerializer):
    class Meta:
        model = Author
        fields = "__all__"


class BookSerializer(serializers.ModelSerializer):
    reviews = serializers.ListField(child=serializers.CharField(), required=False)
    summary = serializers.SerializerMethodField()

    class Meta:
        model = Book
        fields = ["id", "title", "isbn", "pages", "book_format", "author", "publisher", "reviews", "summary", "created"]
        read_only_fields = ["created"]

    def validate_title(self, value):
        return value.strip()


class ResetSerializer(serializers.Serializer):
    confirm = serializers.BooleanField()
    reason = serializers.CharField(max_length=200, allow_blank=True, default="")


class LoginSerializer(serializers.Serializer):
    username = serializers.CharField()
    password = serializers.CharField(write_only=True)
