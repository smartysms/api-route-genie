from rest_framework import generics, mixins
from rest_framework.decorators import api_view
from rest_framework.response import Response
from rest_framework.views import APIView
from rest_framework import viewsets


class StatusView(APIView):
    def get(self, request):
        return Response("ok")

    def post(self, request):
        return Response("ok")


@api_view()
def ping(request):
    return Response("pong")


@api_view(["POST", "PUT"])
def both(request):
    return Response("both")


class NoteList(generics.ListCreateAPIView):
    pass


class NoteDetail(generics.RetrieveUpdateDestroyAPIView):
    pass


class ManualViewSet(viewsets.ViewSet):
    def list(self, request):
        return Response([])

    def create(self, request):
        return Response({})
