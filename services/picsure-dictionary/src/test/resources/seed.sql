--
-- PostgreSQL database dump
--

-- Dumped from database version 16.2
-- Dumped by pg_dump version 16.2

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
    concept_type character varying(32) NOT NULL DEFAULT 'Interior',
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
-- Data for Name: concept_node; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.concept_node (concept_node_id, dataset_id, concept_type, name, display, concept_path, parent_id) FROM stdin;
1	1	Interior	ACT Diagnosis ICD-10	ACT Diagnosis I	\\\\ACT Diagnosis ICD-10\\\\	\N
10	1	Interior	ACT Lab Test Results	ACT Lab Test Re	\\\\ACT Lab Test Results\\\\	\N
16	1	Interior	ACT Medications	ACT Medications	\\\\ACT Medications\\\\	\N
21	1	Interior	ACT Procedures CPT	ACT Procedures 	\\\\ACT Procedures CPT\\\\	\N
17	1	Interior	C [Preparations]	C [Preparations	\\\\ACT Medications\\\\C [Preparations]\\\\	16
18	1	Interior	Cefpodoxime	Cefpodoxime	\\\\ACT Medications\\\\C [Preparations]\\\\Cefpodoxime\\\\	16
19	1	Interior	Cefpodoxime Oral Tablet	Cefpodoxime Ora	\\\\ACT Medications\\\\C [Preparations]\\\\Cefpodoxime\\\\Cefpodoxime Oral Tablet\\\\	16
20	1	Categorical	Cefpodoxime 100 Mg Oral Tablet	Cefpodoxime 100	\\\\ACT Medications\\\\C [Preparations]\\\\Cefpodoxime\\\\Cefpodoxime Oral Tablet\\\\Cefpodoxime 100 Mg Oral Tablet\\\\	16
22	1	Interior	Medicine Services and Procedures	Medicine Servic	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\	21
11	1	Interior	Virus	Virus	\\\\ACT Lab Test Results\\\\Virus\\\\	10
2	1	Interior	J00-J99 Diseases of the respiratory system (J00-J99)	J00-J99 Disease	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\	1
3	1	Interior	J40-J47 Chronic lower respiratory diseases (J40-J47)	J40-J47 Chronic	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\	1
4	1	Interior	J45 Asthma	J45 Asthma	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\	1
5	1	Interior	J45.5 Severe persistent asthma	J45.5 Severe pe	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.5 Severe persistent asthma\\\\	1
6	1	Categorical	J45.51 Severe persistent asthma with (acute) exacerbation	J45.51 Severe p	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.5 Severe persistent asthma\\\\J45.51 Severe persistent asthma with (acute) exacerbation\\\\	1
7	1	Categorical	J45.52 Severe persistent asthma with status asthmaticus	J45.52 Severe p	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.5 Severe persistent asthma\\\\J45.52 Severe persistent asthma with status asthmaticus\\\\	1
8	1	Interior	J45.9 Other and unspecified asthma	J45.9 Other and	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.9 Other and unspecified asthma\\\\	1
9	1	Interior	J45.90 Unspecified asthma	J45.90 Unspecif	\\\\ACT Diagnosis ICD-10\\\\J00-J99 Diseases of the respiratory system (J00-J99)\\\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\\\J45 Asthma\\\\J45.9 Other and unspecified asthma\\\\J45.90 Unspecified asthma\\\\	1
12	1	Interior	Hepatitis B virus	Hepatitis B vir	\\\\ACT Lab Test Results\\\\Virus\\\\Hepatitis B virus\\\\	11
13	1	Interior	Hepatitis B virus core Ab	Hepatitis B vir	\\\\ACT Lab Test Results\\\\Virus\\\\Hepatitis B virus\\\\Hepatitis B virus core Ab\\\\	12
23	1	Interior	Neurology and Neuromuscular Procedures	Neurology and N	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\Neurology and Neuromuscular Procedures\\\\	22
27	1	Interior	Non-Face-To-Face Nonphysician Services	Non-Face-To-Fac	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\Non-Face-To-Face Nonphysician Services\\\\	22
14	1	Interior	Hepatitis B virus core Ab [Presence] in Serum by Immunoassay	Hepatitis B vir	\\\\ACT Lab Test Results\\\\Virus\\\\Hepatitis B virus\\\\Hepatitis B virus core Ab\\\\Hepatitis B virus core Ab [Presence] in Serum by Immunoassay\\\\	13
15	1	Interior	Hepatitis B virus core Ab [Presence] in Serum	Hepatitis B vir	\\\\ACT Lab Test Results\\\\Virus\\\\Hepatitis B virus\\\\Hepatitis B virus core Ab\\\\Hepatitis B virus core Ab [Presence] in Serum\\\\	13
24	1	Interior	Special Eeg Testing Procedures	Special Eeg Tes	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\Neurology and Neuromuscular Procedures\\\\Special Eeg Testing Procedures\\\\	23
26	1	Interior	Wada activation test for hemispheric function, including electroencephalographic (EEG) monitoring	Wada activation	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\Neurology and Neuromuscular Procedures\\\\Special Eeg Testing Procedures\\\\Wada activation test for hemispheric function, including electroencephalographic (EEG) monitoring\\\\	24
25	1	Interior	Pharmacological or physical activation requiring physician or other qualified health care professional attendance during EEG recording of activation phase (eg, thiopental activation test)	Pharmacological	\\\\ACT Procedures CPT\\\\Medicine Services and Procedures\\\\Neurology and Neuromuscular Procedures\\\\Special Eeg Testing Procedures\\\\Pharmacological or physical activation requiring physician or other qualified health care professional attendance during EEG recording of activation phase (eg, thiopental activation test)\\\\	24
\.


--
-- Data for Name: concept_node_meta; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.concept_node_meta (concept_node_meta_id, concept_node_id, key, value) FROM stdin;
1	1	Ontology	ACT 10
2	2	Ontology	ACT 10
3	3	Ontology	ACT 10
4	4	Ontology	ACT 10
5	5	Ontology	ACT 10
6	6	Ontology	ACT 10
7	7	Ontology	ACT 10
8	8	Ontology	ACT 10
9	9	Ontology	ACT 10
10	10	Ontology	ACT 10
11	11	Ontology	ACT 10
12	12	Ontology	ACT 10
13	13	Ontology	ACT 10
14	14	Ontology	ACT 10
15	15	Ontology	ACT 10
16	16	Ontology	ACT 10
17	17	Ontology	ACT 10
18	18	Ontology	ACT 10
19	19	Ontology	ACT 10
20	20	Ontology	ACT 10
21	21	Ontology	ACT 10
22	22	Ontology	ACT 10
23	23	Ontology	ACT 10
24	24	Ontology	ACT 10
25	25	Ontology	ACT 10
26	26	Ontology	ACT 10
27	27	Ontology	ACT 10
28	1	TYPE	Interior
29	2	TYPE	Interior
30	3	TYPE	Interior
31	4	TYPE	Interior
32	5	TYPE	Interior
33	8	TYPE	Interior
34	10	TYPE	Interior
35	11	TYPE	Interior
36	12	TYPE	Interior
37	13	TYPE	Interior
38	14	TYPE	Categorical
39	15	TYPE	Categorical
40	6	TYPE	Categorical
41	7	TYPE	Categorical
42	9	TYPE	Categorical
43	16	TYPE	Interior
44	17	TYPE	Interior
45	18	TYPE	Interior
46	19	TYPE	Interior
47	21	TYPE	Interior
48	22	TYPE	Interior
49	23	TYPE	Interior
50	24	TYPE	Interior
51	27	TYPE	Interior
52	25	TYPE	FreeText
53	26	TYPE	FreeText
54	20	TYPE	Continuous
55	20	MIN	0
56	20	MAX	100
57	14	VALUES	true,false
58	15	VALUES	true,false
59	6	VALUES	true,false
60	7	VALUES	true,false
61	9	VALUES	true,false
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
\.


--
-- Data for Name: facet__concept_node; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet__concept_node (facet__concept_node_id, facet_id, concept_node_id) FROM stdin;
1	1	9
2	2	9
3	1	7
4	1	6
\.


--
-- Data for Name: facet_category; Type: TABLE DATA; Schema: public; Owner: picsure
--

COPY public.facet_category (facet_category_id, name, display, description) FROM stdin;
1	site	Site	Filter variables by site
\.


--
-- Name: concept_node_concept_node_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_concept_node_id_seq', 27, true);


--
-- Name: concept_node_meta_concept_node_meta_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_meta_concept_node_meta_id_seq', 61, true);


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

SELECT pg_catalog.setval('public.facet__concept_node_facet__concept_node_id_seq', 4, true);


--
-- Name: facet_category_facet_category_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_category_facet_category_id_seq', 1, true);


--
-- Name: facet_facet_id_seq; Type: SEQUENCE SET; Schema: public; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_facet_id_seq', 2, true);


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
-- PostgreSQL database dump complete
--

