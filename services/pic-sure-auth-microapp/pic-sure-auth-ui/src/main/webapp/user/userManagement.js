define(["backbone","handlebars", "text!user/userManagement.hbs", "text!user/userMenu.hbs", "text!user/userTable.hbs", "text!options/modal.hbs", "picSure/userFunctions"],
		function(BB, HBS, template, userMenuTemplate, userTableTemplate, modalTemplate, userFunctions){
    // HBS.registerHelper("ifUserExists", function(uuid, options){
    //     if(uuid.trim().length > 0){
    //         return options.fn(this);
    //     }
    // });

    var userManagementModel = BB.Model.extend({
    });

    var userManagementView = BB.View.extend({
		initialize : function(opts){
			this.template = HBS.compile(template);
			this.crudUserTemplate = HBS.compile(userMenuTemplate);
			this.modalTemplate = HBS.compile(modalTemplate);
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
        displayUserMenu: function (result) {
            this.$el.html();

            $("#modalDialog", this.$el).show();
            $(".modal-title", this.$el).html("Add user");
            $(".modal-body", this.$el).html(this.crudUserTemplate(result));
        },
        displayUsers: function (result, view) {
            this.userTableTemplate = HBS.compile(userTableTemplate);
            var lar = $('.user-data', view.$el);
            $('.user-data', this.$el).html(this.userTableTemplate(result));

        },
        addUserMenu: function (result) {
            $("#modal-window", this.$el).html(this.modalTemplate({title: "Add user"}));
            $("#modalDialog", this.$el).show();

            $(".modal-body", this.$el).html(this.crudUserTemplate({createOrUpdateUser: true, availableRoles: this.model.get("availableRoles")}));

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
            //var subject = this.$('input[name=subject]').val();
            var roles = "";
            var length = this.$('input:checked').length;
            _.each(this.$('input:checked'), function(role, index){
                if (index == length - 1) {
                    roles += role.value;
                } else {
                    roles += (role.value + ", ");
                }
            });

            var user;
            var requestType;
            if (this.model.get("selectedUser") != null && this.model.get("selectedUser").uuid.trim().length > 0) {
                requestType = "PUT";
                user = [{
                            uuid: this.model.get("selectedUser").uuid,
                            userId: userId,
                            subject: userId,
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
            userFunctions.deleteUser(uuid, function (response) {
                alert("User deleted!");
                this.render()
            }.bind(this));
            console.log("Clicked delete user");
        },

        closeDialog: function () {
		    // cleanup
            this.model.unset("selectedUser");
            $("#modalDialog").hide();
        },
        getUserRoles: function (stringRoles) {
            var roles = stringRoles.split(",").map(function(item) {
                return item.trim();
            });
            this.model.get("selectedUser").roles = roles;
        },
        render : function(){
            this.$el.html(this.template({}));
            userFunctions.getAvailableRoles(function (roles) {
                this.model.set("availableRoles", roles);
            }.bind(this));
            userFunctions.fetchUsers(this, this.displayUsers);
		}
	});

	return {
		View : userManagementView,
        Model: userManagementModel
	};
});