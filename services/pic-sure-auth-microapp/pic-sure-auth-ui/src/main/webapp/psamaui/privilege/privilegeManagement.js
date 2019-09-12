define(["backbone","handlebars",  "privilege/addPrivilege", "text!privilege/privilegeManagement.hbs",
		"text!privilege/privilegeMenu.hbs", "text!privilege/privilegeTable.hbs", "text!options/modal.hbs",
		"picSure/privilegeFunctions", "util/notification","picSure/applicationFunctions"],
		function(BB, HBS, AddPrivilegeView, template, privilegeMenuTemplate,
				 privilegeTableTemplate, modalTemplate, privilegeFunctions,
				 notification, applicationFunctions){
	var privilegeManagementModel = BB.Model.extend({
	});

	var privilegeManagementView = BB.View.extend({
		// connections : connections,
		template : HBS.compile(template),
		crudPrivilegeTemplate : HBS.compile(privilegeMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){
		},
		events : {
			"click .add-privilege-button":   "addPrivilegeMenu",
			"click #edit-privilege-button":  "editPrivilegeMenu",
			"click .close":             "closeDialog",
			"click #cancel-privilege-button":"closeDialog",
			"click .privilege-row":          "showPrivilegeAction",
			"click #delete-privilege-button":"deletePrivilege",
			"change #application-dropdown":"dropdownChange",
			"submit":                   "savePrivilegeAction"
		},
		displayPrivileges: function (result, view) {
			this.privilegeTableTemplate = HBS.compile(privilegeTableTemplate);
			$('.privilege-data', this.$el).html(this.privilegeTableTemplate({privileges:result}));

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
		addPrivilegeMenu: function (result) {
			applicationFunctions.fetchApplications(this, function(applications,view){
				view.showAddPrivilegeMenu({applications : applications}, view);
			});
		},
		showAddPrivilegeMenu: function(result, view) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add Privilege"}));
            $("#modalDialog", this.$el).show();
            var addPrivilegeView = new AddPrivilegeView({el:$('.modal-body'), managementConsole: this, applications:result.applications}).render();
		},
		editPrivilegeMenu: function (events) {
			applicationFunctions.fetchApplications(this, function(applications){
                $(".modal-body", this.$el).html(this.crudPrivilegeTemplate({
                    createOrUpdatePrivilege: true,
                    privilege: this.model.get("selectedPrivilege"),
					applications: applications
                }));
                this.applyOptions(this.model.get("selectedPrivilege"));
            }.bind(this));
		},
		showPrivilegeAction: function (event) {
			var uuid = event.target.id;

			privilegeFunctions.showPrivilegeDetails(uuid, function(result) {
				this.model.set("selectedPrivilege", result);
				$("#modal-window", this.$el).html(this.modalTemplate({title: "Privilege Info"}));
				$("#modalDialog", this.$el).show();
                applicationFunctions.fetchApplications(this, function(applications){
                    $(".modal-body", this.$el).html(this.crudPrivilegeTemplate({
                        createOrUpdatePrivilege: false,
                        privilege: this.model.get("selectedPrivilege"),
                        applications: applications
                    }));
                    this.applyOptions(this.model.get("selectedPrivilege"));
                }.bind(this));
			}.bind(this));
		},
		applyOptions: function (privilege) {
			var options = $("#application-dropdown", this.$el);
			var anyOptionSelected = false;
			_.each(options[0].options, function(option) {
                if (option.value === privilege.application ? privilege.application.uuid : false) {
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
        savePrivilegeAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=privilege_name]').attr('uuid');
            var name = this.$('input[name=privilege_name]').val();
            var description = this.$('input[name=privilege_description]').val();

            var applicationUUID = $('.application-block #uuid')[0].innerHTML;

            var privilege;
            var requestType;
            if (this.model.get("selectedPrivilege") != null && this.model.get("selectedPrivilege").uuid.trim().length > 0) {
                requestType = "PUT";
            }
            else {
                requestType = "POST";
            }

            privilege = [{
                uuid: uuid,
                name: name,
                description: description,
				application:{
                	uuid: applicationUUID
				}
            }];

            privilegeFunctions.createOrUpdatePrivilege(privilege, requestType, function(result) {
                console.log(result);
                this.render();
            }.bind(this));
        },
		deletePrivilege: function (event) {
			var uuid = this.$('input[name=privilege_name]').attr('uuid');
			notification.showConfirmationDialog(function () {

				privilegeFunctions.deletePrivilege(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedPrivilege");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			privilegeFunctions.fetchPrivileges(this, function(privileges){
				this.displayPrivileges.bind(this)
				(
						privileges
				);
			}.bind(this));
		}
	});

	return {
		View : privilegeManagementView,
		Model: privilegeManagementModel
	};
});