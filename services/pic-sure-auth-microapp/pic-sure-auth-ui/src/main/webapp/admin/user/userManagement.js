define(["backbone","handlebars", "user/connections", "user/addUser", "text!user/userManagement.hbs", "text!user/userMenu.hbs", "text!user/userTable.hbs", "text!options/modal.hbs", "picSure/userFunctions", "util/notification"],
		function(BB, HBS, connections, AddUserView, template, userMenuTemplate, userTableTemplate, modalTemplate, userFunctions, notification){
	var userManagementModel = BB.Model.extend({
	});

	var userManagementView = BB.View.extend({
		connections : connections, 
		template : HBS.compile(template),
		crudUserTemplate : HBS.compile(userMenuTemplate),
		modalTemplate : HBS.compile(modalTemplate),
		initialize : function(opts){
			HBS.registerHelper('fieldHelper', function(user, connectionField){
				if (user.generalMetadata == null || user.generalMetadata === '') {
                    return "NO_GENERAL_METADATA";
                }
                else {
					return JSON.parse(user.generalMetadata)[connectionField.id];
				}
			});
		},
		events : {
			"click .add-user-button":   "addUserMenu",
			"click #edit-user-button":  "editUserMenu",
			"click .close":             "closeDialog",
			"click #cancel-user-button":"closeDialog",
			"click .user-row":          "showUserAction",
			"click #delete-user-button":"deleteUser",
			"submit":                   "saveUserAction",
		},
		displayUsers: function (result, view) {
			this.userTableTemplate = HBS.compile(userTableTemplate);
			$('.user-data', this.$el).html(this.userTableTemplate({connections:this.connections}));

		},
		addUserMenu: function (result) {
			$("#modal-window", this.$el).html(this.modalTemplate({title: "Add user"}));
			$("#modalDialog", this.$el).show();
			var addUserView = new AddUserView({el:$('.modal-body'), managementConsole: this}).render();
		},
		editUserMenu: function (events) {
			$(".modal-body", this.$el).html(this.crudUserTemplate({createOrUpdateUser: true, user: this.model.get("selectedUser"), availableRoles: this.model.get("availableRoles")}));
			this.applyCheckboxes();
		},
		applyCheckboxes: function () {
			var checkBoxes = $(":checkbox", this.$el);
			var userRoles = this.model.get("selectedUser").roles;
			_.each(checkBoxes, function (roleCheckbox) {
				if (userRoles.includes(roleCheckbox.value)){
					roleCheckbox.checked = true;
				}
			})
		},
		showUserAction: function (event) {
			var uuid = event.target.id;

			userFunctions.showUserDetails(uuid, function(result) {
				this.model.set("selectedUser", result);
				this.getUserRoles(result.roles);
				$("#modal-window", this.$el).html(this.modalTemplate({title: "User info"}));
				$("#modalDialog", this.$el).show();
				$(".modal-body", this.$el).html(this.crudUserTemplate({createOrUpdateUser: false, user: this.model.get("selectedUser")}));
			}.bind(this));
		},
        saveUserAction: function (e) {
            e.preventDefault();
            var userId = this.$('input[name=userId]').val();
            var email = this.$('input[name=email]').val();
            var uuid = this.$('input[name=uuid]').val();
            var subject = this.$('input[name=subject]').val();
            var general_metadata = this.$('input[name=general_metadata]').val();
            var auth0_metadata = this.$('input[name=auth0_metadata]').val();
            var connectionId = this.$('input[name=connectionId]').val();
            var roles = _.pluck(this.$('input:checked'), "value").join(',');

            var user;
            var requestType;
            if (this.model.get("selectedUser") != null && this.model.get("selectedUser").uuid.trim().length > 0) {
                requestType = "PUT";
                user = [{
                    uuid: uuid,
                    userId: userId,
                    email: email,
                    connectionId: connectionId,
                    generalMetadata: general_metadata,
                    auth0metadata: auth0_metadata,
                    subject: subject,
                    roles: roles}];
            }
            else {
                requestType = "POST";
                user = [{
                    userId: userId,
                    subject: userId,
                    roles: roles}];
            }

            userFunctions.createOrUpdateUser(user, requestType, function(result) {
                console.log(result);
                this.render();
            }.bind(this));
        },
		deleteUser: function (event) {
			var uuid = this.$('input[name=userId]').val();
			notification.showConfirmationDialog(function () {

				userFunctions.deleteUser(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		getUserRoles: function (stringRoles) {
			var roles = stringRoles.split(",").map(function(item) {
				return item.trim();
			});
			this.model.get("selectedUser").roles = roles;
		},
		closeDialog: function () {
			// cleanup
			this.model.unset("selectedUser");
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			userFunctions.getAvailableRoles(function (roles) {
				this.model.set("availableRoles", roles);
			}.bind(this));
			userFunctions.fetchUsers(this, function(users){
				this.displayUsers.bind(this)(
						{
							connections: 
								_.map(this.connections, function(connection){
									return _.extend(connection, 
											{
										users: users.filter(
												function(user){
													return user.connectionId === connection.id;
												})
								})})
						}
				);
			}.bind(this));
		}
	});

	return {
		View : userManagementView,
		Model: userManagementModel
	};
});