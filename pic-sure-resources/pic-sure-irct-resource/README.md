### IRCT Resource

This resource sends a query to IRCT

Sample query:
```
{   "resourceUUID" : "${IRCT-RESOURCE-ID}",
	"resourceCredentials" : {
	                "IRCT_BEARER_TOKEN": ${IRCT-TOKEN}
    }, 
    "query":
  	      {  
  	        "select": [
                    {
                        "alias": "gender", "field": {"pui": "/nahnes/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/M", "dataType":"STRING"}
                    },
                    {
                        "alias": "gender", "field": {"pui": "/nhanes/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/F", "dataType":"STRING"}
                    },
                    {
                        "alias": "age",    "field": {"pui": "/nhanes/Demo/GRIN/GRIN/DEMOGRAPHIC/Age", "dataType":"STRING"}
                    }
                ],
                "where": [
                    {
                        "predicate": "CONTAINS",
                        "field": {
                            "pui": "/nhanes/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/M",
                            "dataType": "STRING"
                        },
                        "fields": {
                            "ENCOUNTER": "YES"
                        }
                    }
                ]
          }
}
```