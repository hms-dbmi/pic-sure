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
-- Name: dict; Type: SCHEMA; Schema: -; Owner: picsure
--

CREATE SCHEMA dict;

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
-- Name: concept_node; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.concept_node (
    concept_node_id integer NOT NULL,
    dataset_id integer NOT NULL,
    name character varying(512) NOT NULL,
    display character varying(512) NOT NULL,
    concept_type character varying(32) DEFAULT 'Interior'::character varying NOT NULL,
    concept_path character varying(10000) DEFAULT 'INVALID'::character varying NOT NULL,
    parent_id integer,
    searchable_fields tsvector
);

--
-- Name: concept_node_concept_node_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: concept_node_meta; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.concept_node_meta (
    concept_node_meta_id integer NOT NULL,
    concept_node_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);

--
-- Name: concept_node_meta_concept_node_meta_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: consent; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.consent (
    consent_id integer NOT NULL,
    dataset_id integer NOT NULL,
    consent_code character varying(512) NOT NULL,
    description character varying(512) NOT NULL,
    authz character varying(512) NOT NULL,
    PARTICIPANT_COUNT INT NOT NULL DEFAULT 12,
    VARIABLE_COUNT INT NOT NULL DEFAULT 99,
    SAMPLE_COUNT INT NOT NULL DEFAULT 14
);

--
-- Name: consent_consent_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: dataset; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.dataset (
    dataset_id integer NOT NULL,
    ref character varying(512) NOT NULL,
    full_name character varying(512) NOT NULL,
    abbreviation character varying(512) NOT NULL,
    description text DEFAULT ''::text
);

--
-- Name: dataset_dataset_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: dataset_meta; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.dataset_meta (
    dataset_meta_id integer NOT NULL,
    dataset_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);

--
-- Name: dataset_meta_dataset_meta_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: facet; Type: TABLE; Schema: dict; Owner: picsure
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
-- Name: facet__concept_node; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.facet__concept_node (
    facet__concept_node_id integer NOT NULL,
    facet_id integer NOT NULL,
    concept_node_id integer NOT NULL
);

--
-- Name: facet__concept_node_facet__concept_node_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: facet_category; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.facet_category (
    facet_category_id integer NOT NULL,
    name character varying(512) NOT NULL,
    display character varying(512) NOT NULL,
    description text DEFAULT ''::text
);

--
-- Name: facet_category_facet_category_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: facet_category_meta; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.facet_category_meta (
    facet_category_meta_id integer NOT NULL,
    facet_category_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);

--
-- Name: facet_category_meta_facet_category_meta_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: facet_facet_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Name: facet_meta; Type: TABLE; Schema: dict; Owner: picsure
--

CREATE TABLE public.facet_meta (
    facet_meta_id integer NOT NULL,
    facet_id integer NOT NULL,
    key character varying(256) NOT NULL,
    value text NOT NULL
);

--
-- Name: facet_meta_facet_meta_id_seq; Type: SEQUENCE; Schema: dict; Owner: picsure
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
-- Data for Name: concept_node; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.concept_node (concept_node_id, dataset_id, name, display, concept_type, concept_path, parent_id, searchable_fields) FROM stdin;
209	14			categorical 	\\Bio Specimens\\	\N	'bio':1 'specimen':2
180	14			categorical 	\\ACT Diagnosis ICD-10\\	\N	'-10':4 'act':1 'diagnosi':2 'icd':3
191	14			categorical 	\\ACT Lab Test Results\\	\N	'act':1 'lab':2 'result':4 'test':3
197	14			categorical 	\\ACT Medications\\	\N	'act':1 'medic':2
202	14			categorical 	\\ACT Procedures CPT\\	\N	'act':1 'cpt':3 'procedur':2
215	14			categorical	\\Consent Type\\	\N	'consent':1 'type':2
219	15			categorical	\\NHANES\\	\N	'nhane':1
226	19			categorical	\\phs000007\\	\N	'phs000007':1
236	18			categorical	\\phs000284\\	\N	'phs000284':1
243	20			categorical	\\phs002385\\	\N	'phs002385':1
247	17				\\phs002715\\	\N	'phs002715':1
250	23			categorical	\\phs002808\\	\N	'phs002808':1
259	21			categorical	\\phs003463\\	\N	'phs003463':1
262	24			categorical	\\phs003566\\	\N	'phs003566':1
265	14			categorical	\\Variant Data Type\\	\N	'data':2 'type':3 'variant':1
181	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\	180	'-10':4 'act':1 'diagnosi':2 'diseas':8 'icd':3 'j00':6,14 'j00-j99':5,13 'j99':7,15 'respiratori':11 'system':12
182	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\	181	'-10':4 'act':1 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12
183	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\	182	'-10':4 'act':1 'asthma':27 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12
184	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\	183	'-10':4 'act':1 'asthma':27,31 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j47':18,25 'j99':7,15 'lower':20 'persist':30 'respiratori':11,21 'sever':29 'system':12
223	15			categorical	\\NHANES\\questionnaire\\	219	'nhane':1 'questionnair':2
263	24	Visit01	Visit01	categorical	\\phs003566\\Visit01\\	262	'phs003566':1 'visit01':2,3
185	14	J45.51 Severe persistent asthma with (acute) exacerbation	J45.51 Severe persistent asthma with (acute) exacerbation	categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.51 Severe persistent asthma with (acute) exacerbation\\	184	'-10':4 'act':1 'acut':37,44 'asthma':27,31,35,42 'chronic':19 'diagnosi':2 'diseas':8,22 'exacerb':38,45 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j45.51':32,39 'j47':18,25 'j99':7,15 'lower':20 'persist':30,34,41 'respiratori':11,21 'sever':29,33,40 'system':12
186	14	J45.52 Severe persistent asthma with status asthmaticus	J45.52 Severe persistent asthma with status asthmaticus	categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.5 Severe persistent asthma\\J45.52 Severe persistent asthma with status asthmaticus\\	184	'-10':4 'act':1 'allerg':50,57,72,81 'approxim':46 'asthma':27,31,35,42,51,58,64,70,79,89 'asthmaticus':38,45,54,61,67,76,85,92 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.5':28 'j45.52':32,39,86 'j47':18,25 'j99':7,15 'lower':20 'persist':30,34,41,49,56,63,69,78,88 'respiratori':11,21 'rhiniti':73,82 'sever':29,33,40,48,55,62,68,77,87 'status':37,44,53,60,66,75,84,91 'synonym':47 'system':12
187	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\	183	'-10':4 'act':1 'asthma':27,32 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12 'unspecifi':31
188	14			categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\	187	'-10':4 'act':1 'asthma':27,32,35 'chronic':19 'diagnosi':2 'diseas':8,22 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j45.90':33 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'system':12 'unspecifi':31,34
189	14	J45.901 Unspecified asthma with (acute) exacerbation	J45.901 Unspecified asthma with (acute) exacerbation	categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.901 Unspecified asthma with (acute) exacerbation\\	188	'-10':4 'act':1 'acut':40,46,50,60,64,74,83 'allerg':55,57,71 'approxim':48 'asthma':27,32,35,38,44,53,58,62,69,78,81 'chronic':19 'diagnosi':2 'diseas':8,22 'exacerb':41,47,51,61,65,75,76,84 'flare':67 'flare-up':66 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j45.90':33 'j45.901':36,42,79 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'rhiniti':56,72 'synonym':49 'system':12 'unspecifi':31,34,37,43,80
231	19			categorical	\\phs000007\\pht000022\\phv00004260\\	230	'phs000007':1 'pht000022':2 'phv00004260':3
232	19	phv00004260	FM219	Continuous	\\phs000007\\pht000022\\phv00004260\\FM219\\	231	'0':17 '1':18 '12':6 'caffein':10 'cola':11 'cup':8 'day':12 'fm219':4,5 'oz':7 'phs000007':1 'pht000022':2 'phv00004260':3 'yes':16
190	14	J45.902 Unspecified asthma with status asthmaticus	J45.902 Unspecified asthma with status asthmaticus	categorical 	\\ACT Diagnosis ICD-10\\J00-J99 Diseases of the respiratory system (J00-J99)\\J40-J47 Chronic lower respiratory diseases (J40-J47)\\J45 Asthma\\J45.9 Other and unspecified asthma\\J45.90 Unspecified asthma\\J45.902 Unspecified asthma with status asthmaticus\\	188	'-10':4 'act':1 'allerg':52,59,72 'approxim':48 'asthma':27,32,35,38,44,50,57,64,67,71,77,83 'asthmaticus':41,47,56,63,70,75,80,86 'chronic':19 'diagnosi':2 'diseas':8,22 'extrins':76 'icd':3 'j00':6,14 'j00-j99':5,13 'j40':17,24 'j40-j47':16,23 'j45':26 'j45.9':28 'j45.90':33 'j45.902':36,42,81 'j47':18,25 'j99':7,15 'lower':20 'respiratori':11,21 'rhiniti':53,60 'status':40,46,55,62,66,69,74,79,85 'synonym':49 'system':12 'unspecifi':31,34,37,43,82
192	14			categorical	\\ACT Lab Test Results\\Virus\\	191	'act':1 'lab':2 'result':4 'test':3 'virus':5
193	14			categorical 	\\ACT Lab Test Results\\Virus\\Hepatitis B virus\\	192	'act':1 'b':7 'hepat':6 'lab':2 'result':4 'test':3 'virus':5,8
194	14			categorical 	\\ACT Lab Test Results\\Virus\\Hepatitis B virus\\Hepatitis B virus core Ab\\	193	'ab':13 'act':1 'b':7,10 'core':12 'hepat':6,9 'lab':2 'result':4 'test':3 'virus':5,8,11
195	14	Hepatitis B virus core Ab	Hepatitis B virus core Ab	categorical 	\\ACT Lab Test Results\\Virus\\Hepatitis B virus\\Hepatitis B virus core Ab\\Hepatitis B virus core Ab [Presence] in Serum by Immunoassay\\	194	'ab':13,18,28 'act':1 'b':7,10,15,25 'core':12,17,27 'hepat':6,9,14,24 'immunoassay':23 'lab':2 'presenc':19 'result':4 'serum':21 'test':3 'virus':5,8,11,16,26
196	14	Hepatitis B virus core Ab	Hepatitis B virus core Ab	categorical 	\\ACT Lab Test Results\\Virus\\Hepatitis B virus\\Hepatitis B virus core Ab\\Hepatitis B virus core Ab [Presence] in Serum\\	194	'ab':13,18,26 'act':1 'b':7,10,15,23 'core':12,17,25 'hepat':6,9,14,22 'lab':2 'presenc':19 'result':4 'serum':21 'test':3 'virus':5,8,11,16,24
198	14			categorical 	\\ACT Medications\\C [Preparations]\\	197	'act':1 'c':3 'medic':2 'prepar':4
199	14			categorical 	\\ACT Medications\\C [Preparations]\\Cefpodoxime\\	198	'act':1 'c':3 'cefpodoxim':5 'medic':2 'prepar':4
200	14			categorical 	\\ACT Medications\\C [Preparations]\\Cefpodoxime\\Cefpodoxime Oral Tablet\\	199	'act':1 'c':3 'cefpodoxim':5,6 'medic':2 'oral':7 'prepar':4 'tablet':8
201	14	Cefpodoxime Oral Tablet	Cefpodoxime Oral Tablet	categorical 	\\ACT Medications\\C [Preparations]\\Cefpodoxime\\Cefpodoxime Oral Tablet\\Cefpodoxime 100 Mg Oral Tablet\\	200	'100':10 'act':1 'c':3 'cefpodoxim':5,6,9,14 'medic':2 'mg':11 'oral':7,12,15 'prepar':4 'tablet':8,13,16
203	14			categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\	202	'act':1 'cpt':3 'medicin':4 'procedur':2,7 'servic':5
204	14			categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\Neurology and Neuromuscular Procedures\\	203	'act':1 'cpt':3 'medicin':4 'neurolog':8 'neuromuscular':10 'procedur':2,7,11 'servic':5
241	18	phv00122507	age	Continuous	\\phs000284\\pht001902\\phv00122507\\age\\	240	'0':11 '21':12 'age':4,5,6 'phs000284':1 'pht001902':2 'phv00122507':3 'yes':10
205	14			categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\Neurology and Neuromuscular Procedures\\Special Eeg Testing Procedures\\	204	'act':1 'cpt':3 'eeg':13 'medicin':4 'neurolog':8 'neuromuscular':10 'procedur':2,7,11,15 'servic':5 'special':12 'test':14
206	14	Special Eeg Testing Procedures	Special Eeg Testing Procedures	categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\Neurology and Neuromuscular Procedures\\Special Eeg Testing Procedures\\Pharmacological or physical activation requiring physician or other qualified health care professional attendance during EEG recording of activation phase (eg, thiopental activation test)\\	205	'act':1 'activ':19,33,37 'attend':28 'care':26 'cpt':3 'eeg':13,30,40 'eg':35 'health':25 'medicin':4 'neurolog':8 'neuromuscular':10 'pharmacolog':16 'phase':34 'physic':18 'physician':21 'procedur':2,7,11,15,42 'profession':27 'qualifi':24 'record':31 'requir':20 'servic':5 'special':12,39 'test':14,38,41 'thiopent':36
207	14	Special Eeg Testing Procedures	Special Eeg Testing Procedures	categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\Neurology and Neuromuscular Procedures\\Special Eeg Testing Procedures\\Wada activation test for hemispheric function, including electroencephalographic (EEG) monitoring\\	205	'act':1 'activ':17 'cpt':3 'eeg':13,24,27 'electroencephalograph':23 'function':21 'hemispher':20 'includ':22 'medicin':4 'monitor':25 'neurolog':8 'neuromuscular':10 'procedur':2,7,11,15,29 'servic':5 'special':12,26 'test':14,18,28 'wada':16
208	14	Medicine Services and Procedures	Medicine Services and Procedures	categorical 	\\ACT Procedures CPT\\Medicine Services and Procedures\\Non-Face-To-Face Nonphysician Services\\	203	'act':1 'cpt':3 'face':10,12 'medicin':4,15 'non':9 'non-face-to-fac':8 'nonphysician':13 'procedur':2,7,18 'servic':5,14,16
210	14			categorical 	\\Bio Specimens\\HumanFluid\\	209	'bio':1 'humanfluid':3 'specimen':2
211	14	SPECIMENS:HF.BLD.000 Quantity	SPECIMENS:HF.BLD.000 Quantity	Continuous	\\Bio Specimens\\HumanFluid\\Blood (Whole)\\SPECIMENS:HF.BLD.000 Quantity\\	210	'000':8,12 '100':18 '500':19 'bio':1 'biosampl':15,17 'blood':4 'gic':14 'hf.bld':7,11 'humanfluid':3 'quantiti':9,13 'specimen':2,6,10 'whole':5 'wholeblood':16
212	14	HumanTissue	HumanTissue	categorical 	\\Bio Specimens\\HumanTissue\\	212	'bio':1 'humantissu':3,4 'specimen':2 'true':5
213	14			categorical 	\\Bio Specimens\\NucleicAcid\\	209	'bio':1 'nucleicacid':3 'specimen':2
214	14	DNA	DNA	categorical 	\\Bio Specimens\\NucleicAcid\\DNA\\	213	'bio':1 'dna':4,5 'nucleicacid':3 'specimen':2 'true':6
216	14	Consent Type	Consent Type	categorical	\\Consent Type\\GIC Consent\\	215	'2':15 'align':10 'consent':1,4,5,18 'gic':3,17 'irb':13 'patient':8 'phase':14 'protocol':16 'type':2,6
217	14	Consent Type	Consent Type	categorical	\\Consent Type\\GIC Legacy Consent\\	215	'2':18 'align':13 'consent':1,5,6,22 'gic':3,20 'irb':16 'legaci':4,21 'patient':9 'phase':17 'protocol':19 'type':2,7
218	14	Consent Type	Consent Type	categorical	\\Consent Type\\Waiver of Consent\\	215	'consent':1,5,6,12,15 'patient':8 'type':2,7 'waiv':11 'waiver':3,13
220	15			categorical	\\NHANES\\examination\\	219	'examin':2 'nhane':1
221	15	physical fitness	physical fitness	categorical	\\NHANES\\examination\\physical fitness\\	220	'examin':2 'fit':4,6 'nhane':1 'physic':3,5
222	15	CVDS1HR	Stage 1 heart rate (per min)	Continuous	\\NHANES\\examination\\physical fitness\\Stage 1 heart rate (per min)	221	'0':63 '1':6,12,50 '150':64 'autom':23 'automat':44 'blood':24 'captur':29,43 'comput':33 'direct':30 'end':47 'enter':55 'event':37 'examin':2 'fit':4 'heart':7,13,17,39,60 'manual':54 'min':10,16 'monitor':27,62 'nhane':1 'per':9,15 'physic':3 'pressure/heart':25 'rate':8,14,18,26,40,61 'read':57 'stage':5,11,49 'system':34 'taken':20 'technician':52 'would':53
224	15	disease	disease	categorical	\\NHANES\\questionnaire\\disease\\	223	'diseas':3,4 'nhane':1 'questionnair':2
225	15	MCQ300a	Any family with heart attack or angina?	categorical	\\NHANES\\questionnaire\\disease\\Any family with heart attack or angina?\\	224	'50':61 'age':59 'an-gi-na':53 'angina':10,17,52 'attack':8,15,50 'biolog':28 'blood':31 'brother':38 'close':27 'deceas':21 'diseas':3 'ever':39 'famili':5,12 'father':34 'gi':55 'health':43 'heart':7,14,49 'includ':18,33 'live':19 'mother':35 'na':56 'nhane':1 'profession':44 'questionnair':2 'relat':32 's/your':26 'sister':36 'sp':25 'told':40 'yes':62
227	19	pht000021	ex0_19s	categorical	\\phs000007\\pht000021\\	226	'19':10 '19s':4 'clinic':5 'cohort':8 'ex0':3 'exam':6,9 'origin':7 'phs000007':1 'pht000021':2
228	19			categorical	\\phs000007\\pht000021\\phv00003844\\	227	'phs000007':1 'pht000021':2 'phv00003844':3
229	19	phv00003844	FL200	Continuous	\\phs000007\\pht000021\\phv00003844\\FL200\\	228	'0':17 '12':6 '3':18 'caffein':10 'cola':11 'cup':8 'day':12 'fl200':4,5 'oz':7 'phs000007':1 'pht000021':2 'phv00003844':3 'yes':16
230	19	pht000022	ex0_20s	categorical	\\phs000007\\pht000022\\	226	'20':10 '20s':4 'clinic':5 'cohort':8 'ex0':3 'exam':6,9 'origin':7 'phs000007':1 'pht000022':2
233	19	pht000033	ex1_4s	categorical	\\phs000007\\pht000033\\	226	'4':10 '4s':4 'clinic':5 'cohort':8 'ex1':3 'exam':6,9 'offspr':7 'phs000007':1 'pht000033':2
234	19			categorical	\\phs000007\\pht000033\\phv00008849\\	233	'phs000007':1 'pht000033':2 'phv00008849':3
235	19	phv00008849	D080	Continuous	\\phs000007\\pht000033\\phv00008849\\D080\\	234	'0':16 '12':6 '5':17 'caffein':10 'cola/day':11 'cup':8 'd080':4,5 'oz':7 'phs000007':1 'pht000033':2 'phv00008849':3 'yes':15
237	18	pht001902	CFS_CARe_Subject_Phenotypes	categorical	\\phs000284\\pht001902\\	236	'adults/children':16 'care':4,7 'cfs':3,8 'cleveland':9 'famili':10 'health':14 'phenotyp':6,15 'phs000284':1 'pht001902':2 'sleep':12 'studi':11 'subject':5
238	18			categorical	\\phs000284\\pht001902\\phv00122360\\	237	'phs000284':1 'pht001902':2 'phv00122360':3
239	18	phv00122360	RECOCC	categorical	\\phs000284\\pht001902\\phv00122360\\RECOCC\\	238	'account':10 'occup':8 'phs000284':1 'pht001902':2 'phv00122360':3 'recent':7 'recocc':4,5
240	18			categorical	\\phs000284\\pht001902\\phv00122507\\	237	'phs000284':1 'pht001902':2 'phv00122507':3
242	18	phv00122622	PERART	Continuous	\\phs000284\\pht001902\\phv00122622\\PERART\\	242	'0':14 '30':15 'artifact':9 'perart':4,5 'phs000284':1 'pht001902':2 'phv00122622':3 'time':7 'yes':13
244	20	AGE	AGE	continuous	\\phs002385\\AGE\\	243	'42':9 'age':2,3,5 'hct':16 'patient':4 'phs002385':1 'pre':15 'pre-hct':14 'transplant':7 'year':8 'yes':13,17
245	20	RACEG	RACEG	categorical	\\phs002385\\RACEG\\	243	'phs002385':1 'race':4 'raceg':2,3 'regroup':5 'report':7 'yes':11
246	20	TXNUM	TXNUM	continuous	\\phs002385\\TXNUM\\	243	'1':6 'hct':13 'number':5 'phs002385':1 'pre':12 'pre-hct':11 'transplant':4 'txnum':2,3 'yes':7,14
248	17	AGE_CATEGORY	age	categorical	\\phs002715\\age\\	247	'21':8 'age':2,3,6 'categori':7 'particip':4 'phs002715':1 'yes':12
249	17	nsrr_ever_smoker	nsrr_ever_smoker	categorical	\\phs002715\\nsrr_ever_smoker\\	247	'ever':3,6 'nsrr':2,5 'phs002715':1 'smoker':4,7,8 'status':9 'yes':10,14
251	23			categorical	\\phs002808\\1 Administrative Data Forms\\	250	'1':2 'administr':3 'data':4 'form':5 'phs002808':1
252	23	\\phs002808\\1 Administrative Data Forms\\AFC No Future Contact\\	1 Administrative Data Forms / AFC No Future Contact	categorical	\\phs002808\\1 Administrative Data Forms\\AFC No Future Contact\\	251	'1':2,10 'administr':3,11 'afc':6,14 'contact':9,17 'data':4,12 'form':5,13 'futur':8,16 'phs002808':1
253	23	AFCA03A	AFCA03A	categorical	\\phs002808\\1 Administrative Data Forms\\AFC No Future Contact\\AFCA03A\\	252	'1':2 'administr':3 'afc':6,12 'afca03a':10,11 'consent':19 'contact':9,17,23 'data':4 'end':15 'form':5 'futur':8,16,21 'numom2b':22 'phs002808':1 'reason':13 'withdrew':18 'yes':27
254	23	\\phs002808\\1 Administrative Data Forms\\H01 Heart Health Study Contact Information\\	1 Administrative Data Forms / H01 Heart Health Study Contact Information	categorical	\\phs002808\\1 Administrative Data Forms\\H01 Heart Health Study Contact Information\\	251	'1':2,12 'administr':3,13 'contact':10,20 'data':4,14 'form':5,15 'h01':6,16 'health':8,18 'heart':7,17 'inform':11,21 'phs002808':1 'studi':9,19
255	23	V5AD09A5_SP	V5AD09A5_SP	categorical	\\phs002808\\1 Administrative Data Forms\\H01 Heart Health Study Contact Information\\V5AD09A5_SP\\	254	'1':2 'administr':3 'care':27 'contact':10 'data':4 'doctor':24 'field':39 'follow':20 'form':5 'h01':6 'health':8,26 'heart':7 'infect':40 'inform':11 'kidney':36 'phs002808':1 'problem':21 'profession':28 'sp':13,15 'specifi':38 'studi':9 'told':29 'v5a':16 'v5ad09a5':12,14 'yes':42
256	23			categorical	\\phs002808\\3a Visit Forms\\	250	'3a':2 'form':4 'phs002808':1 'visit':3
257	23	\\phs002808\\3a Visit Forms\\V5A Maternal Interview 2-7 Years Postpartum\\T01H01B\\	3a Visit Forms / V5A Maternal Interview 2-7 Years Postpartum	categorical	\\phs002808\\3a Visit Forms\\V5A Maternal Interview 2-7 Years Postpartum\\	256	'-7':9,19 '2':8,18 '3a':2,12 'form':4,14 'interview':7,17 'matern':6,16 'phs002808':1 'postpartum':11,21 'v5a':5,15 'visit':3,13 'year':10,20
258	23	T01H01B	T01H01B	categorical	\\phs002808\\3a Visit Forms\\V5A Maternal Interview 2-7 Years Postpartum\\T01H01B\\	257	'-7':9 '2':8 '3a':2 'blood':23 'current':17 'form':4 'high':22 'interview':7 'matern':6 'medic':19 'phs002808':1 'postpartum':11 'prescrib':18 'pressur':24 't01':14 't01h01b':12,13 'v5a':5 'visit':3 'year':10 'yes':25,28
260	21	enrollment_alcohol_and_tobacco	alcohol_and_tobacco	categorical	\\phs003463\\alcohol_and_tobacco_enrollment\\	259	'adult':25 'alcohol':2,6,15 'answers.tsv':20 'associ':10 'enrol':5,14 'file':21 'pair':13 'phs003463':1 'recov':24 'studi':26 'tobacco':4,8,17 'variabl':9 'visit/form':12
261	21	alco_tobaccopre_enrollment_alcohol_and_tobacco	alco_tobaccopre_enrollment_alcohol_and_tobacco	categorical	\\phs003463\\alcohol_and_tobacco_enrollment\\alco_tobaccopre_enrollment_alcohol_and_tobacco\\	260	'alco':6,12 'alcohol':2,9,15 'enrol':5,8,14 'phs003463':1 'tobacco':4,11,17 'tobaccopr':7,13
264	24	visit01_original_ecgsamplebase	VISIT01_ORIGINAL_ECGSAMPLEBASE	continuous	\\phs003566\\Visit01\\VISIT01_ORIGINAL_ECGSAMPLEBASE\\	263	'ecgsamplebas':5,8 'origin':4,7 'phs003566':1 'visit01':2,3,6
266	14	Genotype array	Genotype array	categorical	\\Variant Data Type\\Genotype array\\	265	'array':5,7,9 'data':2 'genotyp':4,6,8 'true':10 'type':3 'variant':1
267	14	Low coverage WGS	Low coverage WGS	categorical	\\Variant Data Type\\Low coverage WGS\\	265	'coverag':5,8,11 'data':2 'low':4,7,10 'true':13 'type':3 'variant':1 'wgs':6,9,12
268	14	WES	WES	categorical	\\Variant Data Type\\WES\\	265	'data':2 'exom':7 'sequenc':8 'true':9 'type':3 'variant':1 'wes':4,5 'whole':6
269	14	WGS	WGS	categorical	\\Variant Data Type\\WGS\\	265	'data':2 'genom':7 'sequenc':8 'true':9 'type':3 'variant':1 'wgs':4,5 'whole':6
270	26	harmonized_var	harmonized_var	continuous	\\phs003566\\harmonized_var\\	263	'ecgsamplebas':5,8 'origin':4,7 'phs003566':1 'visit01':2,3,6
271	26	value_example	value_example	continuous	\\phs003566\\value_example\\	263	'ecgsamplebas':5,8 'origin':4,7 'phs003566':1 'visit01':2,3,6
\.


--
-- Data for Name: concept_node_meta; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.concept_node_meta (concept_node_meta_id, concept_node_id, key, value) FROM stdin;
19	186	description	Approximate Synonyms:\nSevere persistent allergic asthma in status asthmaticus\nSevere persistent allergic asthma with status asthmaticus\nSevere persistent asthma in status asthmaticus\nSevere persistent asthma with allergic rhinitis in status asthmaticus\nSevere persistent asthma with allergic rhinitis with status asthmaticus
20	186	values	["J45.52 Severe persistent asthma with status asthmaticus"]
21	189	description	Approximate Synonyms:\nAcute exacerbation of asthma with allergic rhinitis\nAllergic asthma with acute exacerbation\nAsthma, with acute exacerbation (flare-up)\nAsthma, with allergic rhinitis with acute exacerbation\nExacerbation of asthma
22	189	values	["J45.901 Unspecified asthma with (acute) exacerbation"]
23	190	description	Approximate Synonyms:\nAsthma with allergic rhinitis in status asthmaticus\nAsthma with allergic rhinitis with status asthmaticus\nAsthma with status\nAsthma with status asthmaticus\nAsthma, allergic with status asthmaticus\nExtrinsic asthma with status asthmaticus
24	190	values	["J45.902 Unspecified asthma with status asthmaticus"]
25	211	description	GIC biosample: wholeblood
26	211	data_source	Biosample
27	212	values	["TRUE"]
28	214	values	["TRUE"]
29	216	description	Those patients who align with the IRB Phase 2 protocols
30	216	values	["GIC Consent"]
31	217	description	Those patients who DO NOT align with the IRB Phase 2 protocols
32	217	values	["GIC Legacy Consent"]
33	218	description	Patients who have waived consent
34	218	values	["Waiver of Consent"]
35	222	description	Heart rate is taken by the automated blood pressure/heart rate monitor and captured directly into the computer system. In the event the heart rate is not captured automatically at the end of stage 1, the technician would manually enter the readings from the heart rate monitor.
37	225	description	Including living and deceased, were any of {SP's/your} close biological that is, blood relatives including father, mother, sisters or brothers, ever told by a health professional that they had a heart attack or angina (an-gi-na) before the age of 50?
38	225	values	["Yes"]
39	229	description	# 12 OZ CUPS OF CAFFEINATED COLA / DAY
41	229	stigmatized	false
42	229	unique_identifier	false
43	229	free_text	false
44	229	bdc_open_access	true
45	232	description	# 12 OZ CUPS OF CAFFEINATED COLA / DAY
47	232	stigmatized	false
48	232	unique_identifier	false
49	232	free_text	false
50	232	bdc_open_access	true
51	235	description	# 12 OZ CUPS OF CAFFEINATED COLA/DAY
53	235	stigmatized	false
54	235	unique_identifier	false
55	235	free_text	false
56	235	bdc_open_access	true
57	239	description	Most recent occupation (A)
58	239	values	["ACCOUNTANT"]
59	241	description	Age
61	241	stigmatized	false
62	241	unique_identifier	false
63	241	free_text	false
64	241	bdc_open_access	true
65	242	description	% of time in artifacts
67	242	stigmatized	false
68	242	unique_identifier	false
69	242	free_text	false
70	242	bdc_open_access	true
71	244	description	Patient age at transplant, years
72	244	values	[42]
73	244	stigmatized	false
74	244	unique_identifier	false
75	244	free_text	false
76	244	bdc_open_access	true
77	244	hct status	pre-hct
78	244	computed variable	yes
79	245	description	Race (regrouped)
80	245	values	["Not Reported"]
81	245	stigmatized	false
82	245	unique_identifier	false
83	245	free_text	false
84	245	bdc_open_access	true
85	246	description	Transplant Number
86	246	values	[1]
87	246	stigmatized	true
88	246	unique_identifier	false
89	246	free_text	false
90	246	bdc_open_access	false
91	246	hct status	pre-hct
92	246	computed variable	yes
93	248	description	Participant's age (category)
94	248	values	[21]
95	248	stigmatized	false
96	248	unique_identifier	false
97	248	free_text	false
98	248	bdc_open_access	true
99	249	description	Smoker status
100	249	values	["true"]
101	249	stigmatized	false
102	249	unique_identifier	false
103	249	free_text	false
104	249	bdc_open_access	true
105	253	description	(AFC) Reason for ending future contact: Withdrew consent for future nuMoM2b contact
106	253	values	["No"]
107	253	unique_identifier	false
108	253	free_text	false
109	253	bdc_open_access	true
110	255	description	(V5A) Which of the following problems have a doctor or health care professional told you that you have with your kidney?: Other - Specify Field
111	255	values	["infection"]
112	255	unique_identifier	false
113	255	free_text	true
114	255	bdc_open_access	false
115	258	description	(T01) Are you currently prescribed medication for your high blood pressure?
116	258	values	["Yes"]
117	258	unique_identifier	false
118	258	free_text	false
119	258	bdc_open_access	true
120	266	description	Genotype array
121	266	values	["TRUE"]
122	267	description	Low coverage WGS
123	267	values	["TRUE"]
124	268	description	Whole exome sequencing
40	229	values	[0, 3]
46	232	values	[0, 1]
52	235	values	[0.57,6.77]
60	241	values	["5E-21", "7E+33"]
125	268	values	["TRUE"]
126	269	description	Whole genome sequencing
127	269	values	["TRUE"]
128	227	description	Clinic Exam, Original Cohort Exam 19
129	230	description	Clinic Exam, Original Cohort Exam 20
130	233	description	Clinic Exam, Offspring Cohort Exam 4
131	237	description	CARe_CFS (Cleveland Family Study) - Sleep and Health Phenotype (Adults/Children)
132	260	description	Variables associated with visit/form pair enrollment_alcohol_and_tobacco in the answers.tsv file in the RECOVER Adult study
133	211	values	[100, 500]
36	222	values	[0, 150]
66	242	values	[0, 30]
134	270	values	[0, 21]
135	271	values	['gremlin', 'origin']
\.


--
-- Data for Name: consent; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.consent (consent_id, dataset_id, consent_code, description, authz) FROM stdin;
4	17	c1	Disease-Specific (Heart, Lung, Blood, and Sleep Disorders, IRB, NPU) (DS-HLBS-IRB-NPU)	/programs/NSRR/projects/NSRR-CFS_DS-HLBS-IRB-NPU
5	18	c1	Disease-Specific (Heart, Lung, Blood, and Sleep Disorders, IRB, NPU) (DS-HLBS-IRB-NPU)	/programs/parent/projects/CFS_
6	19	c1	Health/Medical/Biomedical (IRB, MDS) (HMB-IRB-MDS)	/programs/parent/projects/FHS_
7	19	c2	Health/Medical/Biomedical (IRB, NPU, MDS) (HMB-IRB-NPU-MDS)	/programs/parent/projects/FHS_
8	20	c1	General Research Use (GRU)	/programs/BioLINCC/projects/CIBMTR_
9	21	c1	General Research Use (GRU)	/programs/RECOVER/projects/RC_Adult_
10	22	c1	General Research Use (GRU)	/programs/NSRR/projects/SR_HCHS_
11	23	c1	General Research Use (IRB) (GRU-IRB)	/programs/topmed/projects/nuMoM2b_
12	24	c1	General Research Use (IRB) (GRU-IRB)	/programs/Imaging/projects/SPRINT_
13	25	c1	GRU	
\.


--
-- Data for Name: dataset; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.dataset (dataset_id, ref, full_name, abbreviation, description) FROM stdin;
14	1	Genomic Information Commons	GIC	The GIC utilizes the ACT ontology to ensure data alignment across the sites. This project also includes other variables of interest as defined by the Governance Committee, such as biosamples, consents, etc.
15	2	National Health and Nutrition Examination Survey	NHANES	The National Health and Nutrition Examination Survey (NHANES) is a program of studies designed to assess the health and nutritional status of adults and children in the United States.
16	3	1000 Genomes Project	1000 Genomes	The 1000 Genomes Project created a catalogue of common human genetic variation, using openly consented samples from people who declared themselves to be healthy. The reference data resources generated by the project remain heavily used by the biomedical science community.
17	phs002715	National Sleep Research Resource (NSRR): Cleveland Family Study (CFS)	NSRR CFS	The Cleveland Family Study (CFS) is a family-based study of sleep apnea, consisting of 2,284 individuals (46% African American) from 361 families studied on up to 4 occasions over a period of 16 years. The study began in 1990 with the initial aims of quantifying the familial aggregation of sleep apnea. National Institutes of Health (NIH) renewals provided expansion of the original cohort, including increased minority recruitment, and longitudinal follow-up, with the last exam occurring in February 2006. The CFS was designed to provide fundamental epidemiological data on risk factors for sleep disordered breathing (SDB). The sample was selected by identifying affected probands who had laboratory diagnosed obstructive sleep apnea. All first-degree relatives, spouses and available second-degree relatives of affected probands were studied. In addition, during the first 5 study years, neighborhood control families were identified through a neighborhood proband, and his/her spouses and first-degree relatives. Each exam, occurring at approximately 4-year intervals, included new enrollment as well as follow up exams for previously enrolled subjects. For the first three visits, data, including an overnight sleep study, were collected in participants' homes while the last visit occurred in a general clinical research center (GCRC). Phenotypic characterization of the entire cohort included overnight sleep apnea studies, blood pressure, spirometry, anthropometry and questionnaires. Currently, data of 710 individuals are available for use through BioData Catalyst (with genotype data available through dbGaP).\n\nThe National Sleep Research Resource (NSRR) is a NIH-supported sleep data repository that offers free access to large collections of de-identified physiological signals and related clinical data from a large range of cohort studies, clinical trials and other data sources from children and adults, including healthy individuals from the community and individuals with known sleep or other health disorders. The goals of NSRR are to facilitate rigorous research that requires access to large or more diverse data sets, including raw physiological signals, to promote a better understanding of risk factors for sleep and circadian disorders and/or the impact of sleep disturbances on health-related outcomes. Data from over 15 data sources and more than 40,000 individual sleep studies, many linked to dozens if not hundreds of clinical data elements, are available (as of Feb. 2022). Query tools are available to identify variables of interest, and their meta-data and provenance.
18	phs000284	NHLBI Cleveland Family Study (CFS) Candidate Gene Association Resource (CARe)	CFS	The Cleveland Family Study is the largest family-based study of sleep apnea world-wide, consisting of 2284 individuals (46% African American) from 361 families studied on up to 4 occasions over a period of 16 years. The study was begun in 1990 with the initial aims of quantifying the familial aggregation of sleep apnea. NIH renewals provided expansion of the original cohort (including increased minority recruitment) and longitudinal follow-up, with the last exam occurring in February 2006. Index probands (n=275) were recruited from 3 area hospital sleep labs if they had a confirmed diagnosis of sleep apnea and at least 2 first-degree relatives available to be studied. In the first 5 years of the study, neighborhood control probands (n=87) with at least 2 living relatives available for study were selected at random from a list provided by the index family and also studied. All available first degree relatives and spouses of the case and control probands also were recruited. Second-degree relatives, including half-sibs, aunts, uncles and grandparents, were also included if they lived near the first degree relatives (cases or controls), or if the family had been found to have two or more relatives with sleep apnea. Blood was sampled and DNA isolated for participants seen in the last two exam cycles (n=1447). The sample, which is enriched with individuals with sleep apnea, also contains a high prevalence of individuals with sleep apnea-related traits, including: obesity, impaired glucose tolerance, and HTN.\n\nPhenotyping data have been collected over 4 exam cycles, each occurring ~every 4 years. The last three exams targeted all subjects who had been studied at earlier exams, as well as new minority families and family members of previously studied probands who had been unavailable at prior exams. Data from one, two, three and four visits are available for 412, 630, 329 and 67, participants, respectively. In the first 3 exams, participants underwent overnight in-home sleep studies, allowing determination of the number and duration of hypopneas and apneas, sleep period, heart rate, and oxygen saturation levels; anthropometry (weight, height, and waist, hip, and neck circumferences); resting blood pressure; spirometry; standardized questionnaire evaluation of symptoms, medications, sleep patterns, quality of life, daytime sleepiness measures and health history; venipuncture and measurement of total and HDL cholesterol. The 4th exam (2001-2006) was designed to collect more detailed measurements of sleep, metabolic and CVD phenotypes and included measurement of state-of-the-art polysomnography, with both collection of blood and measurement of blood pressure before and after sleep, and anthropometry, upper airway assessments, spirometry, exhaled nitric oxide, and ECG performed the morning after the sleep study.\n\nData have been collected by trained research assistants or GCRC nurses following written Manuals of Procedures who were certified following standard approaches for each study procedure. Ongoing data quality, with assessment of within or between individual drift, has been monitored on an ongoing basis, using statistical techniques as well as regular re-certification procedures. Between and within scorer reliabilities for key sleep apnea indices have been excellent, with intra-class correlation coefficients (ICCs) exceeding 0.92 for the apnea-hypopnea index (AHI). Sleep staging, assessed with epoch specific comparisons, also demonstrate excellent reliability for stage identification (kappas>0.82). There has been no evidence of significant time trends-between or within scorers- for the AHI variables. We also have evaluated the night-to-night variability of the AHI and other sleep variables in 91 subjects, with each measurement made 1-3 months apart. There is high night to night consistency for the AHI (ICC: 0.80), the arousal index (0.76), and the % sleep time in slow-wave sleep (0.73). We have demonstrated the comparability of the apnea estimates (AHI) determined from limited channel studies obtained at in-home settings with in full in-laboratory polysomnography. In addition to our published validation study, we more recently compared the AHI in 169 Cleveland Family Study participants undergoing both assessments (in-home and in-laboratory) within one week apart. These showed excellent levels of agreement (ICC=0.83), demonstrating the feasibility of examining data from either in-home or in-laboratory studies for apnea phenotyping. Data collected in the GCRC were obtained, when possible, with comparable, if not identical techniques, as were the same measures collected at prior exams performed in the participants' homes. To address the comparability of data collected over different exams, we calculated the crude age-adjusted correlations ~3 year within individual correlations between measures made in the most recent GCRC exam with measures made in a prior exam and demonstrated excellent levels of agreement for BMI (r=.91); waist circumference (0.91); FVC (0.88); and FEV1 (0.86). As expected due to higher biological and measurement variability, 149 somewhat lower 3-year correlations were demonstrated for SBP (0.56); Diastolic BP (0.48); AHI (0.62); and nocturnal oxygen desaturation (0.60).
19	phs000007	Framingham Cohort	FHS	Startup of Framingham Heart Study. Cardiovascular disease (CVD) is the leading cause of death and serious illness in the United States. In 1948, the Framingham Heart Study (FHS) -- under the direction of the National Heart Institute (now known as the National Heart, Lung, and Blood Institute, NHLBI) -- embarked on a novel and ambitious project in health research. At the time, little was known about the general causes of heart disease and stroke, but the death rates for CVD had been increasing steadily since the beginning of the century and had become an American epidemic.\n\nThe objective of the FHS was to identify the common factors or characteristics that contribute to CVD by following its development over a long period of time in a large group of participants who had not yet developed overt symptoms of CVD or suffered a heart attack or stroke.\n\nDesign of Framingham Heart Study. In 1948, the researchers recruited 5,209 men and women between the ages of 30 and 62 from the town of Framingham, Massachusetts, and began the first round of extensive physical examinations and lifestyle interviews that they would later analyze for common patterns related to CVD development. Since 1948, the subjects have returned to the study every two years for an examination consisting of a detailed medical history, physical examination, and laboratory tests, and in 1971, the study enrolled a second-generation cohort -- 5,124 of the original participants' adult children and their spouses -- to participate in similar examinations. The second examination of the Offspring cohort occurred eight years after the first examination, and subsequent examinations have occurred approximately every four years thereafter. In April 2002 the Study entered a new phase: the enrollment of a third generation of participants, the grandchildren of the original cohort. The first examination of the Third Generation Study was completed in July 2005 and involved 4,095 participants. Thus, the FHS has evolved into a prospective, community-based, three generation family study. The FHS is a joint project of the National Heart, Lung and Blood Institute and Boston University.\n\nResearch Areas in the Framingham Heart Study. Over the years, careful monitoring of the FHS population has led to the identification of the major CVD risk factors -- high blood pressure, high blood cholesterol, smoking, obesity, diabetes, and physical inactivity -- as well as a great deal of valuable information on the effects of related factors such as blood triglyceride and HDL cholesterol levels, age, gender, and psychosocial issues. Risk factors have been identified for the major components of CVD, including coronary heart disease, stroke, intermittent claudication, and heart failure. It is also clear from research in the FHS and other studies that substantial subclinical vascular disease occurs in the blood vessels, heart and brain that precedes clinical CVD. With recent advances in technology, the FHS has enhanced its research capabilities and capitalized on its inherent resources by the conduct of high resolution imaging to detect and quantify subclinical vascular disease in the major blood vessels, heart and brain. These studies have included ultrasound studies of the heart (echocardiography) and carotid arteries, computed tomography studies of the heart and aorta, and magnetic resonance imaging studies of the brain, heart, and aorta. Although the Framingham cohort is primarily white, the importance of the major CVD risk factors identified in this group have been shown in other studies to apply almost universally among racial and ethnic groups, even though the patterns of distribution may vary from group to group. In the past half century, the Study has produced approximately 1,200 articles in leading medical journals. The concept of CVD risk factors has become an integral part of the modern medical curriculum and has led to the development of effective treatment and preventive strategies in clinical practice.\n\nIn addition to research studies focused on risk factors, subclinical CVD and clinically apparent CVD, Framingham investigators have also collaborated with leading researchers from around the country and throughout the world on projects involving some of the major chronic illnesses in men and women, including dementia, osteoporosis and arthritis, nutritional deficiencies, eye diseases, hearing disorders, and chronic obstructive lung diseases.\n\nGenetic Research in the Framingham Heart Study. While pursuing the Study's established research goals, the NHLBI and the Framingham investigators has expanded its research mission into the study of genetic factors underlying CVD and other disorders. Over the past two decades, DNA has been collected from blood samples and from immortalized cell lines obtained from Original Cohort participants, members of the Offspring Cohort and the Third Generation Cohort. Several large-scale genotyping projects have been conducted in the past decade. Genome-wide linkage analysis has been conducted using genotypes of approximately 400 microsatellite markers that have been completed in over 9,300 subjects in all three generations. Analyses using microsatellite markers completed in the original cohort and offspring cohorts have resulted in over 100 publications, including many publications from the Genetics Analysis Workshop 13. Several other recent collaborative projects have completed thousands of SNP genotypes for candidate gene regions in subsets of FHS subjects with available DNA. These projects include the Cardiogenomics Program of the NHLBI's Programs for Genomics Applications, the genotyping of ~3000 SNPs in inflammation genes, and the completion of a genome-wide scan of 100,000 SNPs using the Affymetrix 100K Genechip.\n\nFramingham Cohort Phenotype Data. The phenotype database contains a vast array of phenotype information available in all three generations. These will include the quantitative measures of the major risk factors such as systolic blood pressure, total and HDL cholesterol, fasting glucose, and cigarette use, as well as anthropomorphic measures such as body mass index, biomarkers such as fibrinogen and CRP, and electrocardiography measures such as the QT interval. Many of these measures have been collected repeatedly in the original and offspring cohorts. Also included in the SHARe database will be an array of recently collected biomarkers, subclinical disease imaging measures, clinical CVD outcomes as well as an array of ancillary studies. The phenotype data is located here in the top-level study phs000007 Framingham Cohort. To view the phenotype variables collected from the Framingham Cohort, please click on the Variables tab above.
20	phs002385	Hematopoietic Cell Transplant for Sickle Cell Disease (HCT for SCD)	HCT_for_SCD	The Center for International Blood and Marrow Transplant Research (CIBMTR) is a hematopoietic cell transplant registry that was established in 1972 at the Medical College of Wisconsin. The overarching goal of the registry is to study trends in transplantations and to advance the understanding and application of allogeneic hematopoietic cell transplantation for malignant and non-malignant diseases. Included in this dataset are children, adolescents and young adults with severe sickle cell disease who received an allogeneic hematopoietic cell transplant in the United States and provided written informed consent for research.\n\nHematopoietic cell transplant for sickle cell disease is curative. Offering this treatment for patients with severe disease is challenging as only about 20-25% of patients expected to benefit have an HLA-matched sibling. Consequently, several transplantations have utilized an HLA-matched or mismatched unrelated adult donor and HLA-mismatched relative. Transplantation strategies have also evolved over time that has included transplant conditioning regimens of varying intensity, grafts other than bone marrow and novel approaches to overcome the donor-recipient histocompatibility barrier and limit graft-versus-host disease. The data that is available for sickle cell disease transplants have been utilized to report on outcomes after transplantation and compare outcomes after transplantation of grafts HLA-matched related, HLA-mismatched related, HLA-matched and HLA-mismatched unrelated donors. Collectively, these data have advanced our knowledge and understanding of hematopoietic cell transplant for this disease. These data can also serve as contemporaneous controls for comparison with other more recent curative treatments like gene therapy and gene editing.
21	phs003463	Researching COVID to Enhance Recovery (RECOVER): Adult Observational Cohort Study	RECOVER_Adult	
22	phs003543	National Sleep Research Resource (NSRR): (HSHC)	NSRR_HSHC	
23	phs002808	Nulliparous Pregnancy Outcomes Study: Monitoring Mothers-to-be Heart Health Study (nuMoM2b Heart Health Study)	nuMoM2b	
24	phs003566	Systolic Blood Pressure Intervention Trial (SPRINT-Imaging)	SPRINT	
25	phs001963	DEMENTIA-SEQ: WGS in Lewy Body Dementia and Frontotemporal Dementia	DEMENTIA-SEQ	
26	harmonized	My Cool Harmonized Dataset	abv	harmony
\.


--
-- Data for Name: dataset_meta; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.dataset_meta (dataset_meta_id, dataset_id, key, value) FROM stdin;
1	17	focus	Sleep Apnea Syndromes
2	18	focus	Sleep Apnea Syndromes
3	19	focus	Cardiovascular Disease
4	20	focus	Sickle Cell Disease
5	21	focus	Covid-19
6	22	focus	Sleep Apnea Syndromes
7	23	focus	Hypertension
8	24	focus	Imaging
9	25	focus	Lewy Body Disease
10	17	design	Prospective Longitudinal Cohort
11	18	design	Prospective Longitudinal Cohort
12	19	design	Prospective Longitudinal Cohort
13	20	design	Prospective Longitudinal Cohort
14	21	design	Clinical Trial
15	22	design	Prospective Longitudinal Cohort
16	23	design	Prospective Longitudinal Cohort
17	25	design	Case-Control
18	25	category code	Case-Control
19	25	focus display	Lewy Body Disease
20	25	condition coding system	urn:oid:2.16.840.1.113883.6.177
21	25	condition coding code	D020961
22	25	condition coding display	Lewy Body Disease
23	25	description	Lewy body dementia, amyotrophic lateral sclerosis/frontotemporal dementia, and multiple system atrophy are age-related, neurodegenerative syndromes that are poorly understood. Delineating the genetic risk that is driving the pathophysiology of these neurological diseases is fundamental for understanding disease mechanisms and for developing disease-modifying treatments. <br>\\n\\n\\n\\n In version 1 of the study/dbGaP deposition, we performed a whole-genome sequencing study consisting of 7,403 total samples, including 2,633 genomes from patients with Lewy body dementia, 2,641 frontotemporal dementia patients, and 1,980 neurologically healthy controls. Of these, 6,907 were uploaded to dbGaP as the basis of the DementiaSeq, phs001963 dataset. The data relating to these samples are available on dbGaP.\\n\\n\\n\\n In version 2 of this study/dbGaP deposition, we made much of these data available on Anvil. More specifically, data for 6,254 of these samples were also uploaded to the ALS Compute platform on AnVIL. The data for the remaining 653 samples are only available on dbGaP. The dbGaP/AnVIL Table lists the availability of dbGaP and AnVIL for each individual sample: <a style=\\font-family: sans-serif;\\>phd008475</a>.\\n\\nIn version 3 of the study/dbGaP deposition, we added whole-genome sequence data generated using DNA samples obtained from 683 patients diagnosed with multiple system atrophy.\\n
24	25	sponsor display	National Institute on Aging
25	20	additional information	Cure SCi Metadata Catalog
26	17	clinvars	500
27	18	clinvars	12321
28	19	clinvars	12546
29	20	clinvars	7567
30	21	clinvars	654645
31	22	clinvars	434
32	23	clinvars	2
33	24	clinvars	333
34	25	clinvars	653
36	17	participants	23432
37	18	participants	867876
38	19	participants	3435
39	20	participants	33
40	21	participants	6654
41	22	participants	53435
42	23	participants	111
43	24	participants	2222
44	25	participants	65
\.


--
-- Data for Name: facet; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.facet (facet_id, facet_category_id, name, display, description, parent_id) FROM stdin;
20	2	LOINC	LOINC	\N	\N
21	2	PhenX	PhenX	\N	\N
22	1	1	GIC	\N	\N
23	1	2	National Health and Nutrition Examination Survey	\N	\N
24	1	3	1000 Genomes Project	\N	\N
25	1	phs002715	NSRR CFS	\N	\N
26	1	phs000284	CFS	\N	\N
27	1	phs000007	FHS	\N	\N
28	1	phs002385	HCT_for_SCD	\N	\N
29	1	phs003463	RECOVER_Adult	\N	\N
30	1	phs003543	NSRR_HSHC	\N	\N
31	1	phs002808	nuMoM2b	\N	\N
32	1	phs003566	SPRINT	\N	\N
33	1	phs001963	DEMENTIA-SEQ	\N	\N
55	1	NEST_1	My Nested Facet 1	\N	33
56	1	NEST_2	My Nested Facet 2	\N	33
19	2	gad_7	Generalized Anxiety Disorder Assessment (GAD-7)	\N	\N
18	2	taps_tool	NIDA CTN Common Data Elements = TAPS Tool	\N	\N
\.


--
-- Data for Name: facet__concept_node; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.facet__concept_node (facet__concept_node_id, facet_id, concept_node_id) FROM stdin;
1	22	180
2	22	181
3	22	182
4	22	183
5	22	184
6	22	185
7	22	186
8	22	187
9	22	188
10	22	189
11	22	190
12	22	191
13	22	192
14	22	193
15	22	194
16	22	195
17	22	196
18	22	197
19	22	198
20	22	199
21	22	200
22	22	201
23	22	202
24	22	203
25	22	204
26	22	205
27	22	206
28	22	207
29	22	208
30	22	209
31	22	210
32	22	211
33	22	212
34	22	213
35	22	214
36	22	215
37	22	216
38	22	217
39	22	218
40	23	219
41	23	220
42	23	221
43	23	222
44	23	223
45	23	224
46	23	225
47	27	226
48	27	227
49	27	228
50	27	229
51	27	230
52	27	231
53	27	232
54	27	233
55	27	234
56	27	235
57	26	236
58	26	237
59	26	238
60	26	239
61	26	240
62	26	241
63	26	242
64	28	243
65	28	244
66	28	245
67	28	246
68	25	247
69	25	248
70	25	249
71	31	250
72	31	251
73	31	252
74	31	253
75	31	254
76	31	255
77	31	256
78	31	257
79	31	258
80	29	259
81	29	260
82	29	261
83	32	262
84	32	263
85	32	264
86	22	265
87	22	266
88	22	267
89	22	268
90	22	269
91	18	261
92	20	229
93	21	229
94	21	271
\.


--
-- Data for Name: facet_category; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.facet_category (facet_category_id, name, display, description) FROM stdin;
1	study_ids_dataset_ids	Study IDs/Dataset IDs	
2	nsrr_harmonized	Common Data Element Collection	
3	cde	NSRR Harmonized	
\.


--
-- Data for Name: facet_category_meta; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.facet_category_meta (facet_category_meta_id, facet_category_id, key, value) FROM stdin;
\.


--
-- Data for Name: facet_meta; Type: TABLE DATA; Schema: dict; Owner: picsure
--

COPY public.facet_meta (facet_meta_id, facet_id, key, value) FROM stdin;
1	25	full_name	National Sleep Research Resource
2	26	full_name	Chronic Fatigue Syndrome
3	27	full_name	Framingham Heart Study
\.


--
-- Name: concept_node_concept_node_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_concept_node_id_seq', 269, true);


--
-- Name: concept_node_meta_concept_node_meta_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.concept_node_meta_concept_node_meta_id_seq', 133, true);


--
-- Name: consent_consent_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.consent_consent_id_seq', 13, true);


--
-- Name: dataset_dataset_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.dataset_dataset_id_seq', 25, true);


--
-- Name: dataset_meta_dataset_meta_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.dataset_meta_dataset_meta_id_seq', 25, true);


--
-- Name: facet__concept_node_facet__concept_node_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.facet__concept_node_facet__concept_node_id_seq', 93, true);


--
-- Name: facet_category_facet_category_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_category_facet_category_id_seq', 4, true);


--
-- Name: facet_category_meta_facet_category_meta_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_category_meta_facet_category_meta_id_seq', 1, false);


--
-- Name: facet_facet_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_facet_id_seq', 33, true);


--
-- Name: facet_meta_facet_meta_id_seq; Type: SEQUENCE SET; Schema: dict; Owner: picsure
--

SELECT pg_catalog.setval('public.facet_meta_facet_meta_id_seq', 1, false);


--
-- Name: concept_node_meta concept_node_meta_key_concept_node_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT concept_node_meta_key_concept_node_id_key UNIQUE (key, concept_node_id);


--
-- Name: concept_node_meta concept_node_meta_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT concept_node_meta_pkey PRIMARY KEY (concept_node_meta_id);


--
-- Name: concept_node concept_node_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT concept_node_pkey PRIMARY KEY (concept_node_id);


--
-- Name: consent consent_consent_code_dataset_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.consent
    ADD CONSTRAINT consent_consent_code_dataset_id_key UNIQUE (consent_code, dataset_id);


--
-- Name: consent consent_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.consent
    ADD CONSTRAINT consent_pkey PRIMARY KEY (consent_id);


--
-- Name: dataset_meta dataset_meta_key_dataset_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.dataset_meta
    ADD CONSTRAINT dataset_meta_key_dataset_id_key UNIQUE (key, dataset_id);


--
-- Name: dataset dataset_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT dataset_pkey PRIMARY KEY (dataset_id);


--
-- Name: dataset dataset_ref_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.dataset
    ADD CONSTRAINT dataset_ref_key UNIQUE (ref);


--
-- Name: facet__concept_node facet__concept_node_facet_id_concept_node_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT facet__concept_node_facet_id_concept_node_id_key UNIQUE (facet_id, concept_node_id);


--
-- Name: facet__concept_node facet__concept_node_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT facet__concept_node_pkey PRIMARY KEY (facet__concept_node_id);


--
-- Name: facet_category_meta facet_category_meta_key_facet_category_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_category_meta
    ADD CONSTRAINT facet_category_meta_key_facet_category_id_key UNIQUE (key, facet_category_id);


--
-- Name: facet_category facet_category_name_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_category
    ADD CONSTRAINT facet_category_name_key UNIQUE (name);


--
-- Name: facet_category facet_category_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_category
    ADD CONSTRAINT facet_category_pkey PRIMARY KEY (facet_category_id);


--
-- Name: facet_meta facet_meta_key_facet_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_meta
    ADD CONSTRAINT facet_meta_key_facet_id_key UNIQUE (key, facet_id);


--
-- Name: facet facet_name_facet_category_id_key; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT facet_name_facet_category_id_key UNIQUE (name, facet_category_id);


--
-- Name: facet facet_pkey; Type: CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT facet_pkey PRIMARY KEY (facet_id);


--
-- Name: concept_node_concept_path_idx; Type: INDEX; Schema: dict; Owner: picsure
--

CREATE UNIQUE INDEX concept_node_concept_path_idx ON public.concept_node USING btree (md5((concept_path)::text));


--
-- Name: facet fk_category; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT fk_category FOREIGN KEY (facet_category_id) REFERENCES public.facet_category(facet_category_id);


--
-- Name: concept_node_meta fk_concept_node; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node_meta
    ADD CONSTRAINT fk_concept_node FOREIGN KEY (concept_node_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet__concept_node fk_concept_node; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT fk_concept_node FOREIGN KEY (concept_node_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet_meta fk_facet; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_meta
    ADD CONSTRAINT fk_facet FOREIGN KEY (facet_id) REFERENCES public.facet(facet_id);


--
-- Name: facet__concept_node fk_facet; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet__concept_node
    ADD CONSTRAINT fk_facet FOREIGN KEY (facet_id) REFERENCES public.facet(facet_id);


--
-- Name: facet_category_meta fk_facet_category; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet_category_meta
    ADD CONSTRAINT fk_facet_category FOREIGN KEY (facet_category_id) REFERENCES public.facet_category(facet_category_id);


--
-- Name: concept_node fk_parent; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES public.concept_node(concept_node_id);


--
-- Name: facet fk_parent; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.facet
    ADD CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES public.facet(facet_id);


--
-- Name: dataset_meta fk_study; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.dataset_meta
    ADD CONSTRAINT fk_study FOREIGN KEY (dataset_id) REFERENCES public.dataset(dataset_id);


--
-- Name: concept_node fk_study; Type: FK CONSTRAINT; Schema: dict; Owner: picsure
--

ALTER TABLE ONLY public.concept_node
    ADD CONSTRAINT fk_study FOREIGN KEY (dataset_id) REFERENCES public.dataset(dataset_id);


CREATE TABLE IF NOT EXISTS public.dataset_harmonization
(
    dataset_harmonization_id INT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    harmonized_dataset_id INT NOT NULL,
    source_dataset_id INT NOT NULL,
    UNIQUE (harmonized_dataset_id, source_dataset_id),
    CONSTRAINT fk_harmonized_dataset_id FOREIGN KEY (harmonized_dataset_id) REFERENCES public.dataset (dataset_id),
    CONSTRAINT fk_source_dataset_id FOREIGN KEY (source_dataset_id) REFERENCES public.dataset (dataset_id)
);

INSERT INTO public.dataset_harmonization (dataset_harmonization_id, harmonized_dataset_id, source_dataset_id) VALUES
    (1, 26, 25),
    (1, 26, 24),
    (1, 26, 23),
    (1, 26, 22);


--
-- PostgreSQL database dump complete
--

