define(["backbone","handlebars",  "role/addRole", "text!role/roleManagement.hbs", "text!role/roleMenu.hbs", "text!role/roleTable.hbs", "text!options/modal.hbs", "picSure/roleFunctions", "util/notification","picSure/privilegeFunctions"],
		function(BB, HBS, AddRoleView, template, roleMenuTemplate, roleTableTemplate, modalTemplate, roleFunctions, notification, privilegeFunctions){
	var roleManagementModel = BB.Model.extend({
	});

	var roleManagementView = BB.View.extend({
		// connections : connections,
		template : HBS.compile(template),
		crudRoleTemplate : HBS.compile(roleMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){

		},
		events : {
			"click .add-role-button":   "addRoleMenu",
			"click #edit-role-button":  "editRoleMenu",
			"click .close":             "closeDialog",
			"click #cancel-role-button":"closeDialog",
			"click .role-row":          "showRoleAction",
			"click #delete-role-button":"deleteRole",
			"submit":                   "saveRoleAction",
		},
		displayRoles: function (result, view) {
			this.roleTableTemplate = HBS.compile(roleTableTemplate);
			$('.role-data', this.$el).html(this.roleTableTemplate({roles:result}));

		},
		addRoleMenu: function (result) {
			privilegeFunctions.fetchPrivileges(this, function(privileges,view){
				view.showAddRoleMenu(privileges, view);
			});
		},
		showAddRoleMenu: function(result, view) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add Role"}));
            $("#modalDialog", this.$el).show();
            var addRoleView = new AddRoleView({el:$('.modal-body'), managementConsole: this, privileges:result}).render();
		},
		editRoleMenu: function (events) {
            privilegeFunctions.fetchPrivileges(this, function(privileges,view){
                view.showEditRoleMenu(privileges, view);
            });
		},
		showEditRoleMenu: function(result, view){
            $(".modal-body", this.$el).html(this.crudRoleTemplate({
				createOrUpdateRole: true,
				role: this.model.get("selectedRole"),
				privileges:result
			}));
            this.applyCheckboxes();
		},
		showRoleAction: function (event) {
			var uuid = event.target.id;

			roleFunctions.showRoleDetails(uuid, function(result) {
				this.model.set("selectedRole", result);
                privilegeFunctions.fetchPrivileges(this, function(privileges){
                    $("#modal-window", this.$el).html(this.modalTemplate({title: "Role Info"}));
                    $("#modalDialog", this.$el).show();
                    $(".modal-body", this.$el).html(this.crudRoleTemplate({
						createOrUpdateRole: false,
						role: this.model.get("selectedRole"),
						privileges:privileges
                    }));
                    this.applyCheckboxes();
                }.bind(this));
			}.bind(this));
		},
        applyCheckboxes: function () {
            var checkBoxes = $(":checkbox", this.$el);
            var rolePrivileges = this.model.get("selectedRole").privileges;
            _.each(checkBoxes, function (privilegeCheckbox) {
                _.each(rolePrivileges, function(privilege){
                    if (privilege.name === privilegeCheckbox.name){
                        privilegeCheckbox.checked = true;
                    }
                });
            })
        },
        saveRoleAction: function (e) {
            e.preventDefault();
            var uuid = this.$('input[name=role_name]').attr('uuid');
            var name = this.$('input[name=role_name]').val();
            var description = this.$('input[name=role_description]').val();

            var privileges = [];
            _.each(this.$('input:checked'), function(element) {
            	privileges.push({uuid: element.value});
			});


            var role;
            var requestType;
            if (this.model.get("selectedRole") != null && this.model.get("selectedRole").uuid.trim().length > 0) {
                requestType = "PUT";
            }
            else {
                requestType = "POST";
            }

            role = [{
                uuid: uuid,
                name: name,
                description: description,
				privileges: privileges
            }];

            roleFunctions.createOrUpdateRole(role, requestType, function(result) {
                console.log(result);
                this.render();
            }.bind(this));
        },
		deleteRole: function (event) {
			var uuid = this.$('input[name=role_name]').attr('uuid');
			notification.showConfirmationDialog(function () {

				roleFunctions.deleteRole(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedRole");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			roleFunctions.fetchRoles(this, function(roles){
				this.displayRoles.bind(this)
				(
						roles
				);
			}.bind(this));
		}
	});

	return {
		View : roleManagementView,
		Model: roleManagementModel
	};
});