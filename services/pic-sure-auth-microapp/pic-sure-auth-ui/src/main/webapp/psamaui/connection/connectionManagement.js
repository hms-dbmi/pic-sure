define(["backbone","handlebars", "text!connection/connectionManagement.hbs", "text!connection/connectionTable.hbs", "text!options/modal.hbs", "picSure/picsureFunctions", "text!connection/crudConnection.hbs", "common/session", "util/notification"],
		function(BB, HBS,  template, connectionTableTemplate, modalTemplate, picsureFunctions, crudConnectionTemplate, session, notification){
	var connectionManagementModel = BB.Model.extend({
	});

	var ConnectionModel = BB.Model.extend({
        defaults : {
            uuid : null,
            label : null,
            id : null,
            subPrefix: null,
			requiredFields: [{label: null, id: "email"}]
        }
    });

	var connectionManagementView = BB.View.extend({
        template : HBS.compile(template),
		modalTemplate : HBS.compile(modalTemplate),
        theConnectionTemplate: HBS.compile(crudConnectionTemplate),
		initialize : function(opts){
            HBS.registerHelper('displayField', function(jsonFields){
                var fields = JSON.parse(jsonFields);
                var html = "";
                fields.forEach(function(field){
                    html += "<div>Label: " + field.label + ", ID: " + field.id + "</div>";
                });
                return html;
            });
            HBS.registerHelper('ifEquals', function(a, b, options) {
                if (a === b) {
                    return options.fn(this);
                }
                return options.inverse(this);
            });
		},
		events : {
			"click .add-connection-button":   	"addConnectionMenu",
            "click .add-field-button":			"addConnectionField",
            "click .remove-field-button":		"removeConnectionField",
			"click #edit-button":  				"editConnectionMenu",
            "click #delete-button":				"deleteConnection",
			"click .close":             		"closeDialog",
			"click #cancel-button":				"closeDialog",
			"click .selection-row":     		"showConnectionAction",
			"submit":                   		"saveConnectionAction"
		},
		displayConnections: function (result, view) {
			this.connectionTableTemplate = HBS.compile(connectionTableTemplate);
			$('.connection-data', this.$el).html(this.connectionTableTemplate({connections:result}));

		},
		addConnectionMenu: function (result) {
			$("#modal-window", this.$el).html(this.modalTemplate({title: "Add connection"}));
			$("#modalDialog", this.$el).show();
            var newConnection = new ConnectionModel();
            this.model.set("selectedConnection", newConnection);
            $(".modal-body", this.$el).html(this.theConnectionTemplate({connection: newConnection.attributes, createOrUpdateConnection: true}));
		},
        addConnectionField: function (events) {
            this.updateConnectionModel();
        	this.model.get("selectedConnection").get("requiredFields").push({label: null, id: null});
            $(".modal-body", this.$el).html(this.theConnectionTemplate({connection: this.model.get("selectedConnection").attributes, createOrUpdateConnection: true}));
        },
        removeConnectionField: function (events) {
            var idComponents = event.target.parentNode.id.split("-");
            var elementIndex = idComponents[idComponents.length - 1];

            this.updateConnectionModel();
            if (parseInt(elementIndex) == 0) {
                notification.showWarningMessage("Can't remove first required field.")
            } else {
                this.model.get("selectedConnection").get("requiredFields").splice(elementIndex, 1);
			}
			$(".modal-body", this.$el).html(this.theConnectionTemplate({connection: this.model.get("selectedConnection").attributes, createOrUpdateConnection: true}));
        },
		editConnectionMenu: function (events) {
			$(".modal-body", this.$el).html(this.theConnectionTemplate({createOrUpdateConnection: true, connection: this.model.get("selectedConnection").attributes}));
		},
		showConnectionAction: function (event) {
			var uuid = event.target.id;
			picsureFunctions.getConnection(uuid, false, function(result) {
                var connection = new ConnectionModel(result);
                connection.set("requiredFields", JSON.parse(connection.get("requiredFields")));
				this.model.set("selectedConnection", connection);

				$("#modal-window", this.$el).html(this.modalTemplate({title: "Connection info"}));
				$("#modalDialog", this.$el).show();
				$(".modal-body", this.$el).html(this.theConnectionTemplate({createOrUpdateConnection: false, connection: connection.attributes}));
			}.bind(this));
		},
		updateConnectionModel: function () {
            var theConnection = this.model.get("selectedConnection");
            theConnection.set("uuid", this.$('input[name=uuid]').val());
            theConnection.set("label", this.$('input[name=label]').val());
            theConnection.set("id", this.$('input[name=id]').val());
            theConnection.set("subPrefix", this.$('input[name=subPrefix]').val());
            var requiredFields = theConnection.get("requiredFields");
            _.each(requiredFields, function (requiredField, index, list) {
                requiredField.label = this.$('input[name=required-field-label-' + index + ']').val();
                requiredField.id = this.$('input[name=required-field-id-' + index + ']').val();
            }.bind(this));
        },
        saveConnectionAction: function (e) {
            e.preventDefault();
            var requestType = "POST";
            this.updateConnectionModel();
            var theConnection = this.model.get("selectedConnection");
            theConnection.set("requiredFields", JSON.stringify(theConnection.get("requiredFields")));

            var connections = [theConnection];
			if (theConnection.get("uuid")) {
                requestType = "PUT";
			}
            picsureFunctions.createOrUpdateConnection(connections, requestType, function(result) {
                this.render();
            }.bind(this));
        },
		deleteConnection: function (event) {
			var uuid = this.$('input[name=uuid]').val();
			notification.showConfirmationDialog(function () {
				picsureFunctions.deleteConnection(uuid, function (response) {
					this.render()
				}.bind(this));

			}.bind(this));
		},
		closeDialog: function () {
            var theConnection = this.model.get("selectedConnection");
            this.model.set("selectedConnection", null);
			$("#modalDialog").hide();
		},
		render : function(){
			this.$el.html(this.template({}));
			picsureFunctions.getConnection("", true, function(connections){
				this.displayConnections(connections);
			}.bind(this));
		}
	});

	return {
		View : connectionManagementView,
		Model: connectionManagementModel
	};
});