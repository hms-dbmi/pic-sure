# Dictionary Project

## Purpose

PIC-SURE is an application that allows users to build and export a dataset of phenotypic and genomic data. Building that
dataset involves building a query that sets a series of constraints on variables present in that PIC-SURE. Large
PIC-SURE servers can have tens of thousands of variables, making the query building process complex.
Users need a mechanism to find the variables they want to query on. Currently, different implementations of PIC-SURE
have developed competing solutions to this problem. None of these solutions fully meet PIC-SURE's variable refinement
needs, and all the current solutions are fraught with architectural shortcomings, necessitating the need for a full
replacement.
The Dictionary Project's goal is to create a unified process for enabling the filtration of variables and the creation
of a query. It needs to work across our current projects, and, in anticipation of our push to productize PIC-SURE, it
needs to be workable for new PIC-SURE installations.

## Solution

### UI

The Dictionary Project's end result will be an overhaul of the PIC-SURE Query Builder tab. It will provide the user with
three ways to find variables they wish to query on:

- An expandable tree of variables and their concept paths
- A series of facets on the page's left gutter
- A search bar

The tree of variables can be filtered using the facets or the search bar. Users might also need additional information
to support the filtering options available to them. The UI will have modals that provide meta information about variables, facets, facet categories, and, in relevant environments, studies.

### API

The frontend will be supported by a new Dictionary API. The API will have three core business objects:

- Concepts (`/concepts/`): Variables, the concept paths to them, and associated metadata. The concepts API will support listing (`/concepts/`), viewing details (`/concepts/<study>/<concept path>`), and viewing a tree (`/concepts/tree/<study>/<concept path>?depth=[1-9]+`). The listing API will return a paginated list of concepts; the response will also be filtered using a universal filter object. That object will be shared among all API endpoints, and will be
  described later. The tree API returns a hierarchy of concept nodes descending from the requested node, limiting the response to a depth of `depth`. The details API will be used for viewing details such as meta fields, type information, related facets, and related harmonized variables.
- Facets (`/facets/`): Facets, their categories, and associated metadata. The facets API will support listing (`/facets/`), and viewing details (`/facets/<facet title>/<facet id>`). The listing API will be filtered using the universal filter object. While facets are technically two-dimensional, their hierarchies are quite shallow and broad, so a traditional listing structure will be used, with no pagination. The details API will be used for viewing meta details, related facets, and potentially variable counts for a facet.
- Studies (`/studies/`): Studies, and their metadata. There studies API will support viewing details ((`/studies/<study id>`))

The universal filter object can be `POST`ed to filterable listing APIs to filter results. It has the following structure:

```json
{
  "search": "search terms",
  "facets": [
    {
      "facet_category": "facet title",
      "facet_id": "facet id"
    }
  ]
}
```

In addition to basic GET operations, there is also potential for PATCH/DELETE/POST requests in the future. These would be for administrators that want to make small dictionary changes without redeploying the entire stack. 

### Architecture

The Dictionary project has a UI, API, database, and an ETL. The UI will be part of the unified, next generation monolith currently in progress. The API will be isolated to its own docker container, reachable via the proxy endpoints in the PIC-SURE API. The database will be handled on a per-project basis; in some instances it will make more sense to isolate the database to its own container, but in others, it will remain part of the PIC-SURE relational database monolith.

The queries we will be making to the relational database are in many instances complex, and well outside the standards set out by SQL. This means that this project will likely be locked into whatever database we choose, as the tech debt for moving to a different RDMS would not be justifiable. We would like to use Postgres for this project.