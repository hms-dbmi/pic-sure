--
-- PostgreSQL database dump
--

-- Dumped from database version 16.3
-- Dumped by pg_dump version 16.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: concept_node; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.concept_node (
    concept_node_id integer NOT NULL,
    dataset_id integer NOT NULL,
    name character varying(512) NOT NULL,
    display character varying(512) NOT NULL,
    concept_type character varying(32) DEFAULT 'Interior'::character varying NOT NULL,
    concept_path character varying(10000) DEFAULT 'INVALID'::character varying NOT NULL,
    parent_id integer
);


--
-- Name: concept_node_concept_node_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.concept_node ALTER COLUMN concept_node_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.concept_node_concept_node_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: concept_node_meta; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.concept_node_meta (
    concept_node_meta_id integer NOT NULL,
    concept_node_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);


--
-- Name: concept_node_meta_concept_node_meta_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.concept_node_meta ALTER COLUMN concept_node_meta_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.concept_node_meta_concept_node_meta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: consent; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.consent (
    consent_id integer NOT NULL,
    dataset_id integer NOT NULL,
    consent_code character varying(512) NOT NULL,
    description character varying(512) NOT NULL,
    authz character varying(512) NOT NULL
);


--
-- Name: consent_consent_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.consent ALTER COLUMN consent_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.consent_consent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dataset; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.dataset (
    dataset_id integer NOT NULL,
    ref character varying(512) NOT NULL,
    full_name character varying(512) NOT NULL,
    abbreviation character varying(512) NOT NULL,
    description text DEFAULT ''::text
);


--
-- Name: dataset_dataset_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.dataset ALTER COLUMN dataset_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.dataset_dataset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: dataset_meta; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.dataset_meta (
    dataset_meta_id integer NOT NULL,
    dataset_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);


--
-- Name: dataset_meta_dataset_meta_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.dataset_meta ALTER COLUMN dataset_meta_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.dataset_meta_dataset_meta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facet; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.facet (
    facet_id integer NOT NULL,
    facet_category_id integer NOT NULL,
    name character varying(512) NOT NULL,
    display character varying(512) NOT NULL,
    description text DEFAULT ''::text,
    parent_id integer
);


--
-- Name: facet__concept_node; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.facet__concept_node (
    facet__concept_node_id integer NOT NULL,
    facet_id integer NOT NULL,
    concept_node_id integer NOT NULL
);


--
-- Name: facet__concept_node_facet__concept_node_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.facet__concept_node ALTER COLUMN facet__concept_node_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facet__concept_node_facet__concept_node_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facet_category; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.facet_category (
    facet_category_id integer NOT NULL,
    name character varying(512) NOT NULL,
    display character varying(512) NOT NULL,
    description text DEFAULT ''::text
);


--
-- Name: facet_category_facet_category_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.facet_category ALTER COLUMN facet_category_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facet_category_facet_category_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facet_category_meta; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.facet_category_meta (
    facet_category_meta_id integer NOT NULL,
    facet_category_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);


--
-- Name: facet_category_meta_facet_category_meta_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.facet_category_meta ALTER COLUMN facet_category_meta_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facet_category_meta_facet_category_meta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facet_facet_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.facet ALTER COLUMN facet_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facet_facet_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facet_meta; Type: TABLE; Schema: public; Owner: picsure
--

CREATE TABLE public.facet_meta (
    facet_meta_id integer NOT NULL,
    facet_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);


--
-- Name: facet_meta_facet_meta_id_seq; Type: SEQUENCE; Schema: public; Owner: picsure
--

ALTER TABLE public.facet_meta ALTER COLUMN facet_meta_id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facet_meta_facet_meta_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Data for Name: concept_node; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.concept_node (concept_node_id, dataset_id, name, display, concept_type, concept_path, parent_id) FROM stdin;
44	1	a	A	Categorical	\\\\\\\\A\\\\\\\\	\N
51	1	b	B	Categorical	\\\\\\\\B\\\\\\\\	\N
45	1	1	1	Categorical	\\\\\\\\A\\\\\\\\1\\\\\\\\	44
46	1	0	0	Categorical	\\\\\\\\A\\\\\\\\0\\\\\\\\	44
52	1	0	0	Categorical	\\\\\\\\B\\\\\\\\0\\\\\\\\	51
53	1	2	2	Categorical	\\\\\\\\B\\\\\\\\2\\\\\\\\	51
49	1	x	X	Continuous	\\\\\\\\A\\\\\\\\1\\\\\\\\X\\\\\\\\	45
50	1	z	Z	Continuous	\\\\\\\\A\\\\\\\\1\\\\\\\\Z\\\\\\\\	45
54	1	x	X	Categorical	\\\\\\\\B\\\\\\\\0\\\\\\\\X\\\\\\\\	52
55	1	y	Y	Categorical	\\\\\\\\B\\\\\\\\0\\\\\\\\Y\\\\\\\\	52
56	1	z	Z	Categorical	\\\\\\\\B\\\\\\\\0\\\\\\\\Z\\\\\\\\	52
57	1	y	Y	Continuous	\\\\\\\\B\\\\\\\\2\\\\\\\\Y\\\\\\\\	53
58	1	z	Z	Continuous	\\\\\\\\B\\\\\\\\2\\\\\\\\Z\\\\\\\\	53
47	1	x	X	Categorical	\\\\\\\\A\\\\\\\\0\\\\\\\\X\\\\\\\\	46
48	1	y	Y	Categorical	\\\\\\\\A\\\\\\\\0\\\\\\\\Y\\\\\\\\	46
\.


--
-- Data for Name: concept_node_meta; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.concept_node_meta (concept_node_meta_id, concept_node_id, key, value) FROM stdin;
62	49	MIN	0
63	50	MIN	0
64	57	MIN	0
65	58	MIN	0
66	49	MAX	0
67	50	MAX	0
68	57	MAX	0
69	58	MAX	0
70	44	VALUES	0,1
71	45	VALUES	X,Z
72	46	VALUES	X,Y
73	47	VALUES	foo,bar
74	48	VALUES	foo,bar,baz
75	51	VALUES	0,2
76	52	VALUES	X,Y,Z
77	53	VALUES	Y,Z
78	54	VALUES	bar,baz
79	55	VALUES	bar,baz,qux
80	56	VALUES	foo,bar,baz,qux
81	54	STIGMATIZING	true
82	55	STIGMATIZING	true
83	56	STIGMATIZING	true
\.


--
-- Data for Name: consent; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.consent (consent_id, dataset_id, consent_code, description, authz) FROM stdin;
\.


--
-- Data for Name: dataset; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.dataset (dataset_id, ref, full_name, abbreviation, description) FROM stdin;
1	invalid.invalid	Test Dataset A	test_a	A test dataset
2	invalid.invalid2	Test Dataset B	test_b	A test dataset
\.


--
-- Data for Name: dataset_meta; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.dataset_meta (dataset_meta_id, dataset_id, key, value) FROM stdin;
1	1	size	1000
2	2	size	900
3	1	institution	Mars University
4	2	institution	Bending School
5	1	species	lizard
6	2	species	rocks
\.


--
-- Data for Name: facet; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet (facet_id, facet_category_id, name, display, description, parent_id) FROM stdin;
1	1	bch	BCH	Boston Childrens Hospital	\N
2	1	narnia	Narnia	Narnia	\N
3	2	imaging	Imaging	Data derived from an image	\N
4	2	questionnaire	questionnaire	Data derived from a questionnaire	\N
5	2	lab_test	Lab Test	Data derived from a lab test	\N
\.


--
-- Data for Name: facet__concept_node; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet__concept_node (facet__concept_node_id, facet_id, concept_node_id) FROM stdin;
5	1	44
6	1	45
7	1	46
8	1	47
9	1	48
10	1	49
11	1	50
12	2	51
13	2	52
14	2	53
15	2	54
16	2	55
17	2	56
18	2	57
19	2	58
20	3	47
21	3	48
22	4	49
23	4	50
24	5	54
25	5	55
26	3	56
27	3	57
28	3	58
\.


--
-- Data for Name: facet_category; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet_category (facet_category_id, name, display, description) FROM stdin;
1	site	Site	Filter variables by site
2	data_source	Data Source	What does this data relate to (image, questionnaire...)
\.


--
-- Data for Name: facet_category_meta; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet_category_meta (facet_category_meta_id, facet_category_id, key, value) FROM stdin;
\.


--
-- Data for Name: facet_meta; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet_meta (facet_meta_id, facet_id, key, value) FROM stdin;
1	1	spicy	TRUE
2	2	spicy	FALSE
\.


--
-- Name: concept_node_concept_node_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_concept_node_id_seq', 58, true);


--
-- Name: concept_node_meta_concept_node_meta_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_meta_concept_node_meta_id_seq', 83, true);


--
-- Name: consent_consent_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.consent_consent_id_seq', 1, false);


--
-- Name: dataset_dataset_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.dataset_dataset_id_seq', 2, true);


--
-- Name: dataset_meta_dataset_meta_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.dataset_meta_dataset_meta_id_seq', 6, true);


--
-- Name: facet__concept_node_facet__concept_node_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet__concept_node_facet__concept_node_id_seq', 28, true);


--
-- Name: facet_category_facet_category_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_category_facet_category_id_seq', 2, true);


--
-- Name: facet_category_meta_facet_category_meta_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_category_meta_facet_category_meta_id_seq', 1, false);


--
-- Name: facet_facet_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_facet_id_seq', 5, true);


--
-- Name: facet_meta_facet_meta_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_meta_facet_meta_id_seq', 2, true);


--
-- Name: concept_node_meta concept_node_meta_key_concept_node_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT concept_node_meta_key_concept_node_id_key UNIQUE (key, concept_node_id);


--
-- Name: concept_node_meta concept_node_meta_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT concept_node_meta_pkey PRIMARY KEY (concept_node_meta_id);


--
-- Name: concept_node concept_node_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT concept_node_pkey PRIMARY KEY (concept_node_id);


--
-- Name: consent consent_consent_code_dataset_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.consent
    ADD CONSTRAINT consent_consent_code_dataset_id_key UNIQUE (consent_code, dataset_id);


--
-- Name: consent consent_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.consent
    ADD CONSTRAINT consent_pkey PRIMARY KEY (consent_id);


--
-- Name: dataset_meta dataset_meta_key_dataset_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.dataset_meta
    ADD CONSTRAINT dataset_meta_key_dataset_id_key UNIQUE (key, dataset_id);


--
-- Name: dataset dataset_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT dataset_pkey PRIMARY KEY (dataset_id);


--
-- Name: dataset dataset_ref_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT dataset_ref_key UNIQUE (ref);


--
-- Name: facet__concept_node facet__concept_node_facet_id_concept_node_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT facet__concept_node_facet_id_concept_node_id_key UNIQUE (facet_id, concept_node_id);


--
-- Name: facet__concept_node facet__concept_node_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT facet__concept_node_pkey PRIMARY KEY (facet__concept_node_id);


--
-- Name: facet_category_meta facet_category_meta_key_facet_category_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_category_meta
    ADD CONSTRAINT facet_category_meta_key_facet_category_id_key UNIQUE (key, facet_category_id);


--
-- Name: facet_category facet_category_name_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_category
    ADD CONSTRAINT facet_category_name_key UNIQUE (name);


--
-- Name: facet_category facet_category_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_category
    ADD CONSTRAINT facet_category_pkey PRIMARY KEY (facet_category_id);


--
-- Name: facet_meta facet_meta_key_facet_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_meta
    ADD CONSTRAINT facet_meta_key_facet_id_key UNIQUE (key, facet_id);


--
-- Name: facet facet_name_facet_category_id_key; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT facet_name_facet_category_id_key UNIQUE (name, facet_category_id);


--
-- Name: facet facet_pkey; Type: CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT facet_pkey PRIMARY KEY (facet_id);


--
-- Name: concept_node_concept_path_idx; Type: INDEX; Schema: public; Owner: picsure
--

CREATE UNIQUE INDEX concept_node_concept_path_idx ON public.concept_node USING btree (md5((concept_path)::text));


--
-- Name: facet fk_category; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT fk_category FOREIGN KEY (facet_category_id) REFERENCES public.facet_category(facet_category_id);


--
-- Name: concept_node_meta fk_concept_node; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT fk_concept_node FOREIGN KEY (concept_node_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet__concept_node fk_concept_node; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT fk_concept_node FOREIGN KEY (concept_node_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet__concept_node fk_facet; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT fk_facet FOREIGN KEY (facet_id) REFERENCES public.facet(facet_id);


--
-- Name: concept_node fk_parent; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet fk_parent; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES public.facet(facet_id);


--
-- Name: dataset_meta fk_study; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.dataset_meta
    ADD CONSTRAINT fk_study FOREIGN KEY (dataset_id) REFERENCES public.dataset(dataset_id);


--
-- Name: concept_node fk_study; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT fk_study FOREIGN KEY (dataset_id) REFERENCES public.dataset(dataset_id);


--
-- Name: facet_meta fk_study; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_meta
    ADD CONSTRAINT fk_study FOREIGN KEY (facet_id) REFERENCES public.facet(facet_id);


--
-- Name: facet_category_meta fk_study; Type: FK CONSTRAINT; Schema: public; Owner: picsure
--

ALTER TABLE ONLY public.facet_category_meta
    ADD CONSTRAINT fk_study FOREIGN KEY (facet_category_id) REFERENCES public.facet_category(facet_category_id);


--
-- PostgreSQL database dump complete
--

