from rest_framework import generics, viewsets
from rest_framework.decorators import action
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import Book
from .serializers import AuthorSerializer, BookSerializer, LoginSerializer, ResetSerializer


class BookViewSet(viewsets.ModelViewSet):
    queryset = Book.objects.all()
    serializer_class = BookSerializer

    @action(detail=True, methods=["post"], serializer_class=ResetSerializer)
    def reset(self, request, pk=None):
        return Response("reset")


class AuthorCreate(generics.CreateAPIView):
    serializer_class = AuthorSerializer


class LoginView(APIView):
    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        return Response("ok")


class RawView(APIView):
    def post(self, request):
        return Response(request.data)
