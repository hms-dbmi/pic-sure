alter table dict.concept_node
    drop constraint fk_study;

alter table dict.concept_node
    add constraint fk_study
        foreign key (dataset_id) references dict.dataset
            on delete cascade;

alter table dict.concept_node
    drop constraint fk_parent;

alter table dict.concept_node
    add constraint fk_parent
        foreign key (dataset_id) references dict.dataset
            on delete cascade;

alter table dict.concept_node__remote_dictionary
    drop constraint fk_remote_dictionary;

alter table dict.concept_node__remote_dictionary
    add constraint fk_remote_dictionary
        foreign key (remote_dictionary_id) references dict.remote_dictionary
            on delete cascade;

alter table dict.concept_node__remote_dictionary
    drop constraint fk_concept_node;

alter table dict.concept_node__remote_dictionary
    add constraint fk_concept_node
        foreign key (concept_node_id) references dict.concept_node
            on delete cascade;


alter table dict.concept_node_meta
    drop constraint fk_concept_node;

alter table dict.concept_node_meta
    add constraint fk_concept_node
        foreign key (concept_node_id) references dict.concept_node
            on delete cascade;


alter table dict.dataset_harmonization
    drop constraint fk_harmonized_dataset_id;

alter table dict.dataset_harmonization
    add constraint fk_harmonized_dataset_id
        foreign key (harmonized_dataset_id) references dict.dataset
            on delete cascade;

alter table dict.dataset_harmonization
    drop constraint fk_source_dataset_id;

alter table dict.dataset_harmonization
    add constraint fk_source_dataset_id
        foreign key (source_dataset_id) references dict.dataset
            on delete cascade;


alter table dict.dataset_meta
    drop constraint fk_study;

alter table dict.dataset_meta
    add constraint fk_study
        foreign key (dataset_id) references dict.dataset
            on delete cascade;


alter table dict.facet
    drop constraint fk_category;

alter table dict.facet
    add constraint fk_category
        foreign key (facet_category_id) references dict.facet_category
            on delete cascade;

alter table dict.facet
    drop constraint fk_parent;

alter table dict.facet
    add constraint fk_parent
        foreign key (parent_id) references dict.facet
            on delete cascade;


alter table dict.facet__concept_node
    drop constraint fk_concept_node;

alter table dict.facet__concept_node
    add constraint fk_concept_node
        foreign key (concept_node_id) references dict.concept_node
            on delete cascade;

alter table dict.facet__concept_node
    drop constraint fk_facet;

alter table dict.facet__concept_node
    add constraint fk_facet
        foreign key (facet_id) references dict.facet
            on delete cascade;


alter table dict.facet_category_meta
    drop constraint fk_facet_category;

alter table dict.facet_category_meta
    add constraint fk_facet_category
        foreign key (facet_category_id) references dict.facet_category
            on delete cascade;


alter table dict.facet_meta
    drop constraint fk_facet;

alter table dict.facet_meta
    add constraint fk_facet
        foreign key (facet_id) references dict.facet
            on delete cascade;

-- backwards compatible change, keep db version at 3
UPDATE dict.update_info SET DATABASE_VERSION = 3;
