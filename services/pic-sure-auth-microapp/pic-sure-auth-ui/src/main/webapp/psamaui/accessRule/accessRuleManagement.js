define(["backbone","handlebars",  "accessRule/addAccessRule", "text!accessRule/accessRuleManagement.hbs",
		"text!accessRule/accessRuleMenu.hbs", "text!accessRule/accessRuleTable.hbs", "text!options/modal.hbs",
		"picSure/accessRuleFunctions", "util/notification","picSure/applicationFunctions"],
		function(BB, HBS, AddAccessRuleView, template, accessRuleMenuTemplate,
				 accessRuleTableTemplate, modalTemplate, accessRuleFunctions,
				 notification, applicationFunctions){
	var accessRuleManagementModel = BB.Model.extend({
	});

	var accessRuleManagementView = BB.View.extend({
		// connections : connections,
		template : HBS.compile(template),
		crudAccessRuleTemplate : HBS.compile(accessRuleMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){
		},
		events : {
			"click .add-accessRule-button":   "addAccessRuleMenu",
			"click #edit-accessRule-button":  "editAccessRuleMenu",
			"click .close":             "closeDialog",
			"click #cancel-accessRule-button":"closeDialog",
			"click .accessRule-row":          "showAccessRuleAction",
			"click #delete-accessRule-button":"deleteAccessRule",
			"change #application-dropdown":"dropdownChange",
			"submit":                   "saveAccessRuleAction"
		},
		displayAccessRules: function (result, view) {
			this.accessRuleTableTemplate = HBS.compile(accessRuleTableTemplate);
			$('.accessRule-data', this.$el).html(this.accessRuleTableTemplate({accessRules:result}));

		},
		dropdownChange: function(event){
			var selects = $('#application-dropdown option:selected', this.$el);
			$('.application-block #uuid').text(selects[0].value);
			if (selects[0].value){
                $('.application-block #name').text(selects[0].innerText);
                $('.application-block #description').text(selects[0].attributes.description.value);
			} else {
                $('.application-block #name').text("");
                $('.application-block #description').text("");
			}

		},
		addAccessRuleMenu: function (result) {
			accessRuleFunctions.fetchAccessRules(this, function(accessRules,view){
				view.showAddAccessRuleMenu({accessRules : accessRules}, view);
			});
		},
		showAddAccessRuleMenu: function(result, view) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add AccessRule"}));
            $("#modalDialog", this.$el).show();
            var addAccessRuleView = new AddAccessRuleView({el:$('.modal-body'), managementConsole: this, accessRules:result}).render();
		},
		editAccessRuleMenu: function (events) {
			applicationFunctions.fetchApplications(this, function(applications){
                $(".modal-body", this.$el).html(this.crudAccessRuleTemplate({
                    createOrUpdateAccessRule: true,
                    accessRule: this.model.get("selectedAccessRule"),
					applications: applications
                }));
                this.applyOptions(this.model.get("selectedAccessRule"));
            }.bind(this));
		},
		showAccessRuleAction: function (event) {
			var uuid = event.target.id;

			accessRuleFunctions.showAccessRuleDetails(uuid, function(result) {
				this.model.set("selectedAccessRule", result);
				$("#modal-window", this.$el).html(this.modalTemplate({title: "AccessRule Info"}));
				$("#modalDialog", this.$el).show();
                applicationFunctions.fetchApplications(this, function(applications){
                    $(".modal-body", this.$el).html(this.crudAccessRuleTemplate({
                        createOrUpdateAccessRule: false,
                        accessRule: this.model.get("selectedAccessRule"),
                        applications: applications
                    }));
                    this.applyOptions(this.model.get("selectedAccessRule"));
                }.bind(this));
			}.bind(this));
		},
		applyOptions: function (accessRule) {
			var options = $("#application-dropdown", this.$el);
			var anyOptionSelected = false;
			_.each(options[0].options, function(option) {
                if (option.value === accessRule.application.uuid) {
                    option.selected = true;
                    anyOptionSelected = true;
                } else {
                    option.selected = false;
                }
			});
			if (!anyOptionSelected) {
				options[0].options[0].selected = true;
			}
		},
        saveAccessRuleAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=accessRule_name]').attr('uuid');
            var name = this.$('input[name=accessRule_name]').val();
            var description = this.$('input[name=accessRule_description]').val();

            var applicationUUID = $('.application-block #uuid')[0].innerHTML;

            var accessRule;
            var requestType;
            if (this.model.get("selectedAccessRule") != null && this.model.get("selectedAccessRule").uuid.trim().length > 0) {
                requestType = "PUT";
            }
            else {
                requestType = "POST";
            }

            accessRule = [{
                uuid: uuid,
                name: name,
                description: description,
                type: type
            }];

            accessRuleFunctions.createOrUpdateAccessRule(accessRule, requestType, function(result) {
                console.log(result);
                this.render();
            }.bind(this));
        },
		deleteAccessRule: function (event) {
			var uuid = this.$('input[name=accessRule_name]').attr('uuid');
			notification.showConfirmationDialog(function () {

				accessRuleFunctions.deleteAccessRule(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedAccessRule");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			accessRuleFunctions.fetchAccessRules(this, function(accessRules){
				this.displayAccessRules.bind(this)
				(
						accessRules
				);
			}.bind(this));
		}
	});

	return {
		View : accessRuleManagementView,
		Model: accessRuleManagementModel
	};
});