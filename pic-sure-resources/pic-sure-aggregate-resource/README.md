### Aggregate Query Resource

This resource can send queries to multiple resources at once, and will return all results in a list.  
If a single query fails, however, the entire query will return failure.

Sample query:
```
{   "resourceUUID" : "${AGGREGATE-QUERY-RESOURCE-ID}",
	"resourceCredentials" : {"BEARER_TOKEN": "${JWT-USER-TOKEN}"}, 
    "query": [
  	      {  
  	        "resourceUUID" : "${RESOURCE-1-UUID}",
          	"resourceCredentials": {    "${RESOURCE-1-TOKEN-LABEL}" : "${RESOURCE-1-TOKEN}" },
        	"query": "${QUERY-FORMAT-DEPENDS-ON-RESOURCE}"
          },
		  {  
            "resourceUUID" : "${RESOURCE-2-UUID}",
            "resourceCredentials": {    "${RESOURCE-2-TOKEN-LABEL}" : "${RESOURCE-2-TOKEN}" },
            "query": "${QUERY-FORMAT-DEPENDS-ON-RESOURCE}"
          } 
    ]
}
```