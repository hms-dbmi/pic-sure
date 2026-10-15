## Context

The initial proof-of-concept PIC-SURE AI Chatbot feature will be trained on the PIC-SURE data dictionary from the demo site, which hosts the National Health and Nutrition Examination Survey (NHANES) here: [https://nhanes.hms.harvard.edu/](https://nhanes.hms.harvard.edu/picsureui/)

NHANES is a publicly available dataset that can be used by any researcher. You can learn more about the original dataset here: [https://www.cdc.gov/nchs/nhanes/index.html](https://www.cdc.gov/nchs/nhanes/index.html)

The data is available [here](https://github.com/hms-dbmi/pic-sure-public-datasets/blob/main/NHANES%20abbreviated%20allConcepts.csv.tgz). 

This document will serve to identify key researcher use cases for exploring and finding variables relevant to their research. 

Helpful paper that may be similar to this effort: [https://www.medrxiv.org/content/10.1101/2023.12.13.23299830v1](https://www.medrxiv.org/content/10.1101/2023.12.13.23299830v1)

Papers that could be use for some validation/performance:   
[https://pmc.ncbi.nlm.nih.gov/articles/PMC9934713/](https://pmc.ncbi.nlm.nih.gov/articles/PMC9934713/)  

| Patients | Gender | BMI | Height |
| :---- | :---- | :---- | :---- |
| P1 | Male | 23 | 5’10 |
| P2 | Female | 45 | 6’10 |
| P3 | Female | 34 | 5’11 |
| P4 | Male | 43 |  |

## Researcher Use Cases

1. Context: PIC-SURE doesn’t handle external knowledge about groups of variables.   
   As a researcher, I would like to find **variable names** related to a term of interest. For example, I would like to find all variable names related to obesity, such as body mass index, height and weight, or waist-to-hip ratio.
	1. Currently a researcher would need to search each of these terms individually, and only results with exact text matches will be returned. 
	*Context: in some cases PIC-SURE has some internal knowledge including in the “concept\_path” that could be useful*
	As a researcher, I would like to find variables that fall into the same category. For example, I would like to find all demographics variables, such as sex, age, race/ethnicity. 
	2. Currently a researcher would need to search each of these terms individually, and only results with exact text matches will be returned.
	*Context: Different precision in the prompting.* 
	As a researcher, I would like to type my research question and receive suggested variables to use. For example, I could say that my research is related to cholesterol levels across different demographic groups and receive cholesterol-related and demographics-related variables. 
	3. Currently, researchers may not know what other variables exist that are related to what they are interested in.

2. Context: PIC-SURE doesn’t handle synonyms.
	As a researcher, I would like to find variable names that are the same as my term of interest. For example, I would like to find variables related to BMI, such as a “Body mass index” variable.   
	1. Currently a researcher would need to search each of these terms individually, and only results with exact text matches will be returned. Searching BMI does not return “body mass index” variables.

3. Context: PIC-SURE doesn’t handle spelling or grammatical errors.   
   As a researcher, I would like to find variables that match my term of interest, even if it is misspelled. For example, I may spell “body mass index” incorrectly as “body mas index”  
	1. Currently a researcher would need to search each of these terms individually, and only results with exact text matches will be returned. Searching BMI does not return “body mass index” variables.

4. As a researcher, I would like to see the data dictionary that is included in the NHANES dataset, since I may not be familiar with it. Are there already any relevant groupings? For example:   
	1. Researcher: What groups of variables can I search on?  
	2. Response: The NHANES data is divided into the following groups of variables: demographics, questionnaire, laboratory, and examination.  
	  Demographics  
	  Questionnaire  
		  Pharmaceutical  
		  Food component recall  
		  Supplement use  
		  Housing  
		  Smoking behavior  
		  Occupation  
		  Street drug  
		  Cognitive functioning  
		  …  
	  Laboratory  
		Viral infection  
		Bacterial infection  
		Aging  
		Blood  
		…  
	  Examination  
		...

	Show me all variables (names) related to obesity. (Let me review your findings)  
	Answer:  
	A \- BMI  
	B \- Cholesterol   
	C \- Height  
	D \- Diagnosis in ICD 10 \- Obesity  
	E \- Asthma  
	F  

	Prompt: I want to keep : A, B, C, D and not E  

5. As a researcher, I would like to receive all variables associated with a certain group. For example, I want to type “show me all questionnaire variables” and receive all questionnaire variables.  
	1. With the modernized user interface, the updated NHANES dictionary will include facets. These are groupings of variables based on certain characteristics, like Category, Data Type, etc. A researcher may be interested in what variables are in each category. 

6. As a researcher, I would like to receive all variables associated with multiple parts of variable metadata. For example, I want to type “show me all categorical questionnaire variables” and receive all categorical questionnaire variables.  
     
7. As a researcher, I want to explore the data hierarchy. For example:  
	1. Researcher: I am working with the variable “Current asthma?” in NHANES. What category is this variable in?   
	2. Response: The “Current asthma?” variable is in the Disease category.  
	3. Researcher: What other variables are in the disease category?  
	4. Response: Here are some examples of 5 other variables in the disease category.  
		1. Lymphoma\_self\_report  
		2. Ever osteo arthritis?  
		3. Bone\_cancer\_self\_report  
		4. Other\_skin\_cancer\_self\_report  
		5. Any Diabetes (FBG \>= 126 mg per dL or self-report)  
	5. Researcher: What other variables are in the disease category that are related to the Current asthma? Variable?  
	6. Response: The following variables are in the disease category and are related to the “Current asthma?” variable:  
		1. Ever asthma?  
		2. Any\_chronic\_disease

8. As a researcher, I may ask for the complete data dictionary. The entire data dictionary should not be returned to the researcher, as this would be overwhelming. Instead, the response should reference the overall structure of the data. For example:  
	1. Researcher: What is the data dictionary?  
	2. Response: I cannot show you the complete data dictionary, but the NHANES data is divided into the following groups of variables: demographics, questionnaire, laboratory, and examination.  
		Demographics  
		Questionnaire  
			Pharmaceutical  
			Food component recall  
			Supplement use  
			Housing  
			Smoking behavior  
			Occupation  
			Street drug  
			Cognitive functioning  
			…  
		Laboratory  
			Viral infection  
			Bacterial infection  
			Aging  
			Blood  
			…  
		Examination  
			…

   9. As a researcher, I may provide a list of labs that I am interested in and want to know the PIC-SURE mapping for those labs. For example:
	1. “I am interested in investigating creatinine, albumin, leukocytes, and d dimer. Are these variables available in PIC-SURE?”  
	2. Expected response:  
		1. “There are several creatinine lab variables available: Creatinine (mg per dL), Creatinine, urine (mg per dL), Creatinine, urine (umol per L), 1 Creatinine (mg per dL).   
		2. There are several albumin lab variables available: Albumin (g per dL), Albumin, urine (mg per L), Albumin, urine (ug per mL), Albumin, urine (mg per L) SI.   
		3. Leukocytes are available as White blood cell count (1000 cells per uL).   
		4. D dimer labs are not available.”  

10. As a researcher, I may provide a list of labs that I am interested in and their expected units, and want to know the PIC-SURE mapping for these labs as well as if any of those labs have different units than I am expecting. For example:  
	1. I am interested in investigating creatinine (mg/dL), albumin (mg/dL), leukocytes (count), and d dimer (FEU). Are these variables available in PIC-SURE?  
	2. Expected Response:  
		1. “There are 3 creatinine variables that use mg/dL units: Creatinine (mg per dL), Creatinine, urine (mg per dL), 1 Creatinine (mg per dL).  
		2. There are no albumin variables that use mg/dL units.   
		3. Leukocytes are available as White blood cell count (1000 cells per uL)  
		4. D dimer labs are not available.”

BONUS: nice to have:
As a researcher, I might not know how to start exploring PIC-SURE. I may ask for an example use case. I would expect to receive variable names that are meaningfully associated.

BONUS \- more complex use cases:
As a researcher, I would like to type my research question and receive a response when there aren’t variables related to my question. For example, if my research question is related to sickle cell disease, the NHANES dataset is not related to this since there is no information on this disease. 

----

**POTENTIAL IDEAS FOR INCREMENTALLY ADDING THE CHATBOT TO PIC-SURE** (Danielle’s back of napkins, trying to be Emily, but failing) 

Option 1: Add a new navigation link and add the chatbot as a stand-alone page
Option 2: Incorporate with the existing search box `[ placeholder...      | AI ASsisted Search | Search ]`
