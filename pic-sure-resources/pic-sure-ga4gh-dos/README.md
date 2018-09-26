### GA4GH DOS Api Server Resource

A sample query to list all components of the underlying resource

```
{
	"resourceCredentials": {
		"token": "JWT-TOKEN-FOR-USER"
	},
	"query": {
		"queryString": "54f6ca28-94c4-4efd-88fc-a8fa2374ce5a"
	}
}
```

To search for a specific resource, identified by the `id` attribute of the metadat:
```
{
	"resourceCredentials": {
		"token": "whocares"
	},
	"query": {
		"searchTerm": "54f6ca28-94c4-4efd-88fc-a8fa2374ce5a"
	}
}
```
