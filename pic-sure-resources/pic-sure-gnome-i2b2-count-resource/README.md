### Gnome-I2B2 Count Resource

This resource sends a query to I2B2, a query to gNOME, and returns the count of how many ids are shared between  the two results.


Sample query:
```
{   "resourceUUID" : "${GNOME-I2B2-COUNT-RESOURCE-ID}",
	"resourceCredentials" : {
	                "GNOME_BEARER_TOKEN": "${GNOME-TOKEN}",
	                "I2B2_BEARER_TOKEN": ${I2B2-TOKEN}
    }, 
    "query": [
  	      "i2b2" :
  	      {  
  	        "select": [
                    {
                        "alias": "gender", "field": {"pui": "/i2b2-wildfly-grin-patient-mapping/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/M", "dataType":"STRING"}
                    },
                    {
                        "alias": "gender", "field": {"pui": "/i2b2-wildfly-grin-patient-mapping/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/F", "dataType":"STRING"}
                    },
                    {
                        "alias": "age",    "field": {"pui": "/i2b2-wildfly-grin-patient-mapping/Demo/GRIN/GRIN/DEMOGRAPHIC/Age", "dataType":"STRING"}
                    }
                ],
                "where": [
                    {
                        "predicate": "CONTAINS",
                        "field": {
                            "pui": "/i2b2-wildfly-grin-patient-mapping/Demo/GRIN/GRIN/DEMOGRAPHIC/SEX/M",
                            "dataType": "STRING"
                        },
                        "fields": {
                            "ENCOUNTER": "YES"
                        }
                    }
                ]
          },
          "gnome":
		  {  
            "where":
            	[
            		{ "field" : {
            				"pui" : "/gnome/query_rest.cgi",
            				"dataType" : "STRING"
            			},
            			"predicate" : "CONTAINS",
            			"fields" : {
            				"qtype" : "variants",
            				"vqueries" : ["chr16,2120487,2120487,G,A"]
            			}
            			
            		}
            	]
          } 
    ]
}
```