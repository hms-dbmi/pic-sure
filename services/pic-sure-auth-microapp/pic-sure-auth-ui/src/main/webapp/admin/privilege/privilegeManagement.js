define(["backbone","handlebars",  "privilege/addPrivilege", "text!privilege/privilegeManagement.hbs", "text!privilege/privilegeMenu.hbs", "text!privilege/privilegeTable.hbs", "text!options/modal.hbs", "picSure/privilegeFunctions", "util/notification","picSure/privilegeFunctions"],
		function(BB, HBS, AddPrivilegeView, template, privilegeMenuTemplate, privilegeTableTemplate, modalTemplate, privilegeFunctions, notification, privilegeFunctions){
	var privilegeManagementModel = BB.Model.extend({
	});

	var privilegeManagementView = BB.View.extend({
		// connections : connections,
		template : HBS.compile(template),
		crudPrivilegeTemplate : HBS.compile(privilegeMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){
			HBS.registerHelper('fieldHelper', function(privilege, connectionField){
				if (privilege.generalMetadata == null || privilege.generalMetadata === '') {
                    return "NO_GENERAL_METADATA";
                }
                else {
					return JSON.parse(privilege.generalMetadata)[connectionField.id];
				}
			});
            HBS.registerHelper('displayEmail', function(privilege){
				var c = privilege.connectionId;
                var emailField = _.where(connections, {id: privilege.connectionId})[0].emailField;
				if (!privilege.email) {
                    return JSON.parse(privilege.generalMetadata)[emailField];
				}
                return privilege.email;
            });
		},
		events : {
			"click .add-privilege-button":   "addPrivilegeMenu",
			"click #edit-privilege-button":  "editPrivilegeMenu",
			"click .close":             "closeDialog",
			"click #cancel-privilege-button":"closeDialog",
			"click .privilege-row":          "showPrivilegeAction",
			"click #delete-privilege-button":"deletePrivilege",
			"submit":                   "savePrivilegeAction",
		},
		displayPrivileges: function (result, view) {
			this.privilegeTableTemplate = HBS.compile(privilegeTableTemplate);
			$('.privilege-data', this.$el).html(this.privilegeTableTemplate({privileges:result}));

		},
		addPrivilegeMenu: function (result) {
			privilegeFunctions.fetchPrivileges(this, function(privileges,view){
				view.showAddPrivilegeMenu({privileges : privileges}, view);
			});
		},
		showAddPrivilegeMenu: function(result, view) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add Privilege"}));
            $("#modalDialog", this.$el).show();
            var addPrivilegeView = new AddPrivilegeView({el:$('.modal-body'), managementConsole: this, privileges:result}).render();
		},
		editPrivilegeMenu: function (events) {
			$(".modal-body", this.$el).html(this.crudPrivilegeTemplate({createOrUpdatePrivilege: true, privilege: this.model.get("selectedPrivilege")}));
			// this.applyCheckboxes();
		},
		// applyCheckboxes: function () {
		// 	var checkBoxes = $(":checkbox", this.$el);
		// 	var privilegePrivileges = this.model.get("selectedPrivilege").privileges;
		// 	_.each(checkBoxes, function (privilegeCheckbox) {
		// 		if (privilegePrivileges.includes(privilegeCheckbox.value)){
		// 			privilegeCheckbox.checked = true;
		// 		}
		// 	})
		// },
		showPrivilegeAction: function (event) {
			var uuid = event.target.id;

			privilegeFunctions.showPrivilegeDetails(uuid, function(result) {
				this.model.set("selectedPrivilege", result);
				$("#modal-window", this.$el).html(this.modalTemplate({title: "Privilege Info"}));
				$("#modalDialog", this.$el).show();
				$(".modal-body", this.$el).html(this.crudPrivilegeTemplate({createOrUpdatePrivilege: false, privilege: this.model.get("selectedPrivilege")}));
			}.bind(this));
		},
        savePrivilegeAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=privilege_name]').attr('uuid');
            var name = this.$('input[name=privilege_name]').val();
            var description = this.$('input[name=privilege_description]').val();

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
                description: description
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
		// getPrivilegePrivileges: function (stringPrivileges) {
		// 	var privileges = stringPrivileges.split(",").map(function(item) {
		// 		return item.trim();
		// 	});
		// 	this.model.get("selectedPrivilege").privileges = privileges;
		// },
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedPrivilege");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			// privilegeFunctions.getAvailablePrivileges(function (privileges) {
			// 	this.model.set("availablePrivileges", privileges);
			// }.bind(this));
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