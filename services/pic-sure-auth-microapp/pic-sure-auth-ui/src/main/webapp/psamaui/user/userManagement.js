define(["backbone","handlebars", "user/addUser", "text!user/userManagement.hbs",
	"text!user/userDetails.hbs", "text!user/userTable.hbs",
	"text!options/modal.hbs", "picSure/userFunctions", "picSure/picsureFunctions", "util/notification"],
	function(BB, HBS,  AddUserView, template, userDetailsTemplate,
			userTableTemplate, modalTemplate, userFunctions, picsureFunctions, notification){
	var userManagementModel = BB.Model.extend({
	});

	var userManagementView = BB.View.extend({
		template : HBS.compile(template),
		crudUserTemplate : HBS.compile(userDetailsTemplate),
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
			HBS.registerHelper('requiredFieldValue', function(userMetadata, metadataId){
				if (userMetadata)
					return userMetadata[metadataId];
				else
					return "";
			});
			HBS.registerHelper('displayUserRoles', function(roles){
				return _.pluck(roles, "name").join(", ");
			});
		},
		connections : function(callback){
			picsureFunctions.getConnection("", false, callback);
		},		
		events : {
			"click .add-user-button":   "addUserMenu",
			"click #edit-user-button":  "editUserMenu",
			"click .close":             "closeDialog",
			"click #cancel-user-button":"closeDialog",
			"click .user-row":          "showUserAction",
			"click #switch-status-button":"deactivateUser",
//			"submit":                   "saveUserAction",
			"click .btn-show-inactive":	"toggleInactive"
		},
		updateUserTable: function(connections){
			$('.user-data', this.$el).html(this.userTableTemplate({connections:connections}));
		},
		displayUsers: function (result, view) {
			this.userTableTemplate = HBS.compile(userTableTemplate);
			this.updateUserTable(result.connections);
		},
		addUserMenu: function (result) {
			$("#modal-window", this.$el).html(this.modalTemplate({title: "Add user"}));
			$("#modalDialog", this.$el).show();
			var addUserView = new AddUserView({el:$('.modal-body'), managementConsole: this}).render();
		},
		showUserAction: function (event) {
			var uuid = event.target.id;
			userFunctions.showUserDetails(uuid, function(result) {
				this.connections(function(connections){
					var requiredFields = _.where(connections, {id: result.connection.id})[0].requiredFields;
					if (result.generalMetadata){
						result.generalMetadata = JSON.parse(result.generalMetadata);
					}
					this.model.set("selectedUser", result);
					$("#modal-window", this.$el).html(this.modalTemplate({title: "User info"}));
					$("#modalDialog", this.$el).show();
					$(".modal-body", this.$el).html(this.crudUserTemplate({createOrUpdateUser: false, user: this.model.get("selectedUser"), requiredFields: JSON.parse(this.model.get("selectedUser").connection.requiredFields)}));
				}.bind(this));
			}.bind(this));
		},
		editUserMenu: function (events) {
            if ($(".noty_type__alert").length > 0) return;
			var user = this.model.get("selectedUser");
			this.connections(function(connections){
				var requiredFields = _.where(connections, {id: user.connection.id})[0].requiredFields;
				$(".modal-body", this.$el).html(this.crudUserTemplate({createOrUpdateUser: true, user: user, availableRoles: this.model.get("availableRoles"), requiredFields: requiredFields}));
				this.applyCheckboxes();
                $("input[name=email]").attr('disabled', true);
			}.bind(this));
		},
		applyCheckboxes: function () {
			var checkBoxes = $(":checkbox", this.$el);
			var userRoles = this.model.get("selectedUser").roles;
			_.each(checkBoxes, function (roleCheckbox) {
				_.each(userRoles, function (userRole) {
					if (userRole.uuid == roleCheckbox.value) {
						roleCheckbox.checked = true;
					}
				})
			})
		},
//		saveUserAction: function (e) {
//			e.preventDefault();
//			var user
//			if (this.model.get("selectedUser") != null && this.model.get("selectedUser").uuid.trim().length > 0) {
//				user = this.model.get("selectedUser");
//			}
//			else {
//				user = {
//						subject: "",
//						roles: []}
//			}
//			user.userId = this.$('input[name=userId]').val();
//			user.auth0metadata = this.$('input[name=auth0metadata]').val();
//			user.subject = this.$('input[name=subject]').val();
//			user.connection = {
//					id: this.$('input[name=connectionId]').val()
//			};
//			var general_metadata = {};
//			_.each($('#required-fields input[type=text]'), function(entry){
//				general_metadata[entry.name] = entry.value
//			});
//			user.generalMetadata = JSON.stringify(general_metadata);
//			user.email = general_metadata["email"] ? general_metadata["email"] : email; // synchronize email with metadata
//			
//			$(".error").hide();
//	        var emailReg = /^([\w-\.]+@([\w-]+\.)+[\w-]{2,4})?$/;
//	        if(!emailReg.test(user.email)) {
//	        	$('input[name=email]').after('<span class="error">Enter a valid email address.</span>');
//	        	return false; 
//        	}
//			
//			var roles = [];
//			_.each(this.$('input:checked'), function (checkbox) {
//				roles.push({uuid: checkbox.value});
//			})
//			user.roles = roles;
//			userFunctions.createOrUpdateUser([user], user.uuid == null ? 'POST' : 'PUT', function(result) {
//				console.log(result);
//				this.render();
//			}.bind(this));
//		},
		deactivateUser: function (event) {
			try {
				var user = this.model.get('selectedUser');
				user.active = !user.active;
				if (!user.subject) {
					user.subject = null;
				}
				if (!user.roles) {
					user.roles = [];
				}
				user.generalMetadata = JSON.stringify(user.generalMetadata);
				notification.showConfirmationDialog(function () {
					userFunctions.createOrUpdateUser([user], 'PUT', function (response) {
						this.render()
					}.bind(this));
				}.bind(this));
			} catch (err) {
				console.error(err.message);
				notification.showFailureMessage('Failed to deactivate user. Contact administrator.')
			}
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
		toggleInactive: function (event) {
			var id = event.target.id
			$('#inactive-' + id, this.$el).toggle();
			var toggleButton = $('.btn-show-inactive#' + id + ' span', this.$el);
			if (toggleButton.hasClass('glyphicon-chevron-down'))
				toggleButton.removeClass('glyphicon-chevron-down').addClass('glyphicon-chevron-up');
			else
				toggleButton.removeClass('glyphicon-chevron-up').addClass('glyphicon-chevron-down');
		},
		updateRoles : function(){
			var model = this.model;
			userFunctions.getAvailableRoles(function (roles) {
				model.set("availableRoles", roles);
			});
		},
		render : function(){
			this.$el.html(this.template({}));
			this.updateRoles();
			userFunctions.fetchUsers(this, function(userList){
				var users = [];
				var inactiveUsers = [];
				_.each(userList, function(user){
					if (user.active) {
						users.push(user);
					}
					else {
						inactiveUsers.push(user);
					}
				});
				this.connections(function(connections){
					this.displayUsers.bind(this)({
						connections:
							_.map(connections, function(connection){
								var localCon = connection;
								if(typeof connection.requiredFields === "string"){
									// TODO : this should not be necessary
									connection.requiredFields = JSON.parse(connection.requiredFields);									
								}
								return _.extend(connection, {
									users: users.filter(
											function(user){
												if (user.connection)
													return user.connection.id === connection.id;
												else
													return false;
											}),
											inactiveUsers: inactiveUsers.filter(function(user){
												if (user.connection)
													return user.connection.id === connection.id;
												else
													return false;
											})
								})
							})
					});
				}.bind(this));
			}.bind(this));
		}
	});

	return {
		View : userManagementView,
		Model: userManagementModel
	};
});