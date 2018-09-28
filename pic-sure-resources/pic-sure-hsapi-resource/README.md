### HSAPI Commons Resource

This resource queries the [CommonsShare API](https://beta.commonsshare.org/)

The following endpoints are supported:
* /hsapi/resource/
* /haspi/resource/{id}
* /hsapi/resource/{id}/files/
* /hsapi/resource/{id}/files/{pathname}/
* /hsapi/resource/{id}/folders/{pathname}/

`resource` is the entity being queried; `files` and `folders` are the subentities.  

Sample resource query:
```
{   "resourceUUID" : "${HSAPI-RESOURCE-ID}",
    "query": 
          {  
  	        "entity": "resource
          }
}
```

Sample resource query by page:
```
{   "resourceUUID" : "${HSAPI-RESOURCE-ID}",
    "query": 
          {  
  	        "entity": "resource,
  	        "page": "2"
          }
}
```

Sample files query:
```
{   "resourceUUID" : "${HSAPI-RESOURCE-ID}",
    "query": 
          {  
  	        "entity": "resource,
  	        "id": "123-abc",
  	        "subentity": "files"
          }
}
```

Sample single file query:
```
{   "resourceUUID" : "${HSAPI-RESOURCE-ID}",
    "query": 
          {  
  	        "entity": "resource,
  	        "id": "123-abc",
  	        "subentity": "files",
  	        "pathname": "/content/filename.csv"
          }
}
```